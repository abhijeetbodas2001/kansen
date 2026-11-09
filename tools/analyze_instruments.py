"""Analyze references into oscillator envelopes and residual-noise levels.
No waveform samples are included in the generated model. Run Python using uv;
optional analysis libraries can be installed by uv pip --target into /tmp.
"""
import argparse
import hashlib
import copy
import json
from pathlib import Path
from model_bank import write_model_bank
import numpy as np
from scipy.io import wavfile
from scipy.ndimage import gaussian_filter1d
from scipy.signal import butter, hilbert, resample_poly, sosfiltfilt

parser = argparse.ArgumentParser()
parser.add_argument('references', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--reuse-harmonium', type=Path, help='Reuse measured harmonium profiles with matching reference hashes')
args = parser.parse_args()
SR = 44100
DT = .005
N = 8192
FFT = 32768
window = np.hanning(N)
frequencies = np.fft.rfftfreq(FFT, 1 / SR)
NOISE_EDGES = [80,160,250,400,630,1000,1600,2500,4000,6300,10000,16000]
notes = {'harmonium': [name+str(octave) for octave in (3,4)
                       for name in ('C','Cs','D','Ds','E','F','Fs','G','Gs','A','As','B')
                       if name!='Fs' or octave!=4] + ['C5','Cs5','D5']}
def midi(note):
    return (int(note[-1])+1)*12 + {'C':0,'Cs':1,'D':2,'Ds':3,'E':4,'F':5,'Fs':6,'G':7,'Gs':8,'A':9,'As':10,'B':11}[note[:-1]]
sources = [(instrument,midi(note),f'{instrument}_{note}.wav',440*2**((midi(note)-69)/12))
           for instrument, names in notes.items() for note in names]
sources = [(*source, 0.0, None) for source in sources]
models=[]
reusable = [] if args.reuse_harmonium is None else json.loads(args.reuse_harmonium.read_text())['models']
for instrument, note, filename, expected, region_start, region_end in sources:
    path=args.references/filename
    cached=next((m for m in reusable if m['instrument']==instrument=='harmonium' and m['midi']==note
                 and not m.get('syntheticExtension') and m['sha256']==hashlib.sha256(path.read_bytes()).hexdigest()),None)
    if cached is not None:
        models.append(cached)
        continue
    DT = .005
    sr,raw=wavfile.read(path)
    x=raw.astype(np.float64)
    if np.issubdtype(raw.dtype,np.integer):x/=np.iinfo(raw.dtype).max
    if x.ndim>1:x=x.mean(axis=1)
    if sr!=SR:x=resample_poly(x,SR,sr)
    x=x[round(region_start*SR):round(region_end*SR) if region_end else None]
    x-=np.mean(x)
    # Equal reference loudness makes interpolation depend on register, not mic gain.
    x *= .1 / max(1e-9, np.sqrt(np.mean(x*x)))
    onset=0 if region_start else max(0,np.flatnonzero(abs(x)>max(abs(x))*.012)[0]-44)
    duration=2.5
    available=min(len(x)-onset,round(duration*SR))
    x=x[onset:onset+available]
    x=np.pad(x,(0,max(0,round(duration*SR)-len(x))))
    times=np.arange(0,duration+DT*.5,DT)
    samples=np.arange(len(x))/SR
    padded=np.pad(x,(N//2,N))
    analysis_times=np.arange(.04,min(duration,.6,available/SR-.04),.02)
    spectra=np.array([np.fft.rfft(padded[round(t*SR):round(t*SR)+N]*window,FFT) for t in analysis_times])
    power=np.mean(abs(spectra)**2,axis=0)
    candidates=np.linspace(expected*.97,expected*1.03,901)
    scores=np.array([sum(np.interp(f*np.arange(1,9),frequencies,power)/np.sqrt(np.arange(1,9))) for f in candidates])
    fundamental=candidates[np.argmax(scores)]
    def refine(p):
        logs=np.log(np.maximum(power[p-1:p+2],1e-20));den=logs[0]-2*logs[1]+logs[2]
        delta=0 if abs(den)<1e-15 else .5*(logs[0]-logs[2])/den
        return (p+np.clip(delta,-.5,.5))*SR/FFT
    mode_freqs=[]
    for h in range(1,min(80,int(18000/fundamental))+1):
        region=np.flatnonzero(abs(frequencies-fundamental*h)<min(fundamental*.3, max(18,h*fundamental*.015)))
        mode_freqs.append(refine(region[np.argmax(power[region])]))
    mode_freqs=sorted(mode_freqs)
    analytic=hilbert(x)
    residual=x.copy()
    modes=[]
    fundamental_phase=None
    for index,freq in enumerate(mode_freqs):
        separation=min(freq-mode_freqs[index-1] if index else fundamental,
                       mode_freqs[index+1]-freq if index+1<len(mode_freqs) else fundamental)
        cutoff=min(100,max(6,separation*.36))
        carrier=np.exp(2j*np.pi*freq*samples)
        # Complex demodulation separates each resonance and preserves fast envelopes.
        base=sosfiltfilt(butter(2,cutoff,fs=SR,output='sos'),analytic*np.conjugate(carrier))
        amplitude=abs(base)
        residual-=np.real(base*carrier)
        if max(amplitude)<max(abs(x))*.0005:continue
        levels=np.interp(times,samples,amplitude,left=0,right=0)
        reference_index=min(round(.06*SR),len(x)-1)
        phase=np.angle(base[reference_index])+np.pi/2
        ratio=round(freq/fundamental)
        mode={'ratio':round(float(ratio),7),'phase':round(float(phase%(2*np.pi)),7),
              'amplitudes':np.round(levels,7).tolist()}
        angles=np.unwrap(np.angle(base))+np.pi/2
        reliable=amplitude>max(amplitude)*.015
        if reliable.sum()>2:
            angles=np.interp(samples,samples[reliable],angles[reliable])
        harmonic=round(freq/fundamental)
        angles += 2*np.pi*(freq-harmonic*fundamental)*samples
        if fundamental_phase is None:
            fundamental_phase=angles.copy()
        angles -= harmonic*fundamental_phase
        mode['ratio']=float(harmonic)
        mode['phase']=round(float(angles[reference_index]%(2*np.pi)),7)
        mode['phases']=np.round(np.interp(times,samples,angles),7).tolist()
        modes.append(mode)
    # Analyze the residual AFTER subtracting modes; don't turn harmonic leakage into hiss.
    noise_n=1024
    noise_fft=2048;noise_window=np.hanning(noise_n)
    residual=np.pad(residual,(noise_n//2,noise_n))
    residual_spectrum=np.array([abs(np.fft.rfft(residual[round(t*SR):round(t*SR)+noise_n]*noise_window,noise_fft))**2 for t in times])
    noise_freq=np.fft.rfftfreq(noise_fft,1/SR)
    noise=[]
    for lo,hi in zip(NOISE_EDGES,NOISE_EDGES[1:]):
        mask=(noise_freq>=lo)&(noise_freq<hi)
        rms=np.sqrt(residual_spectrum[:,mask].sum(axis=1)*2/(noise_fft*np.sum(noise_window**2)))
        noise.append(np.round(rms,7).tolist())
    if instrument=='harmonium':
        # Keep changes in reed colour, but remove bellows-pressure loudness dips.
        # Otherwise a held training key inherits the recording's pumping volume.
        energy=np.sqrt(sum(np.array(m['amplitudes'])**2/2 for m in modes)
                       +sum(np.array(band)**2 for band in noise))
        steady=np.median(energy[(times>=.65)&(times<=2.15)])
        blend=np.clip(times/.25,0,1)
        blend=blend*blend*(3-2*blend)
        gain=(1-blend)+blend*np.clip(steady/np.maximum(energy,1e-9),.25,4)
        for mode in modes:
            mode['amplitudes']=np.round(np.array(mode['amplitudes'])*gain,7).tolist()
        noise=[np.round(np.array(band)*gain,7).tolist() for band in noise]
    models.append(dict(instrument=instrument,midi=note,referenceHz=round(float(fundamental),6),stepSeconds=DT,
                       modes=modes,noise=noise,noiseEdges=NOISE_EDGES,referenceFile=filename,
                       referenceStart=region_start,referenceEnd=region_end,
                       sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    print(f'{instrument} {note}: {fundamental:.3f} Hz, {len(modes)} modes, {len(times)} frames',flush=True)
# Above the recorded keyboard, preserve the measured body spectral envelope.
# Shifting every harmonic unchanged exaggerates the upper-register buzz. A bounded
# warp (0=no correction, 1=fixed body envelope) is selected against recorded notes.
harmonium=[m for m in models if m['instrument']=='harmonium']
def harmonic_levels(model):
    result=np.zeros(160)
    for mode in model['modes']:
        result[round(mode['ratio'])-1]=np.sqrt(np.mean(np.array(mode['amplitudes'])[130:430]**2))
    return result

def register_gains(model, target, warp):
    levels=harmonic_levels(model)
    harmonics=np.arange(1,len(levels)+1)
    shape=gaussian_filter1d(np.log(np.maximum(levels,1e-7)),1)
    shifted=np.interp(harmonics*2**((target-model['midi'])*warp/12),harmonics,shape,right=shape[-1])
    gains=np.exp(shifted-shape)
    gains[harmonics*440*2**((target-69)/12)>18000]=0
    gains*=np.linalg.norm(levels)/max(1e-9,np.linalg.norm(levels*gains))
    return gains

candidates=np.linspace(0,1,21)
errors=[]
for warp in candidates:
    distances=[]
    for upper in harmonium:
        if upper['midi']<65:continue
        target=harmonic_levels(upper);target/=np.linalg.norm(target)
        for lower in harmonium:
            if 6<=upper['midi']-lower['midi']<=12:
                predicted=harmonic_levels(lower)*register_gains(lower,upper['midi'],warp)
                predicted/=np.linalg.norm(predicted)
                distances.append(np.linalg.norm(predicted-target))
    errors.append(np.mean(distances))
warp=float(candidates[np.argmin(errors)])
anchor=max(harmonium,key=lambda m:m['midi'])
for note in range(anchor['midi']+1,96):
    extended=copy.deepcopy(anchor)
    extended['midi']=note
    extended['referenceMidi']=anchor['midi']
    extended['syntheticExtension']=True
    extended['registerWarp']=warp
    gains=register_gains(anchor,note,warp)
    for mode in extended['modes']:
        mode['amplitudes']=np.round(np.array(mode['amplitudes'])*gains[round(mode['ratio'])-1],7).tolist()
    models.append(extended)
print(f'Harmonium upper-register warp {warp:.2f}; cross-register mean spectral distance {errors[0]:.4f} -> {min(errors):.4f}',flush=True)
write_model_bank(models, args.output)
print(f'{len(models)} profiles, {args.output.stat().st_size/1048576:.2f} MiB',flush=True)
