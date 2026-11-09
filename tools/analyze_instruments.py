"""Analyze references into oscillator envelopes and residual-noise levels.
No waveform samples are included in the generated model. Run Python using uv;
optional analysis libraries can be installed by uv pip --target into /tmp.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, find_peaks, hilbert, resample_poly, sosfiltfilt

parser = argparse.ArgumentParser()
parser.add_argument('references', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
SR = 44100
DT = .005
N = 8192
FFT = 32768
window = np.hanning(N)
frequencies = np.fft.rfftfreq(FFT, 1 / SR)
NOISE_EDGES = [80,160,250,400,630,1000,1600,2500,4000,6300,10000,16000]
notes = {'harmonium':['C3','Ds3','Fs3','A3','C4','Ds4','G4','A4','C5','D5'],
         'flute':['C4','E4','A4','C5','E5','A5','C6','E6','A6','C7']}
def midi(note):
    return (int(note[-1])+1)*12 + {'C':0,'Ds':3,'E':4,'Fs':6,'G':7,'A':9,'D':2}[note[:-1]]
sources = [(instrument,midi(note),f'{instrument}_{note}.wav',440*2**((midi(note)-69)/12))
           for instrument, names in notes.items() for note in names]
sources += [('sitar',52,'sitar.wav',164.81),('tabla',63,'tabla_na_o.wav',311.6)]
models=[]
for instrument, note, filename, expected in sources:
    path=args.references/filename
    sr,raw=wavfile.read(path)
    x=raw.astype(np.float64)
    if np.issubdtype(raw.dtype,np.integer):x/=np.iinfo(raw.dtype).max
    if x.ndim>1:x=x.mean(axis=1)
    if sr!=SR:x=resample_poly(x,SR,sr)
    x-=np.mean(x)
    onset=max(0,np.flatnonzero(abs(x)>max(abs(x))*.012)[0]-44)
    percussive=instrument in ('sitar','tabla')
    duration=2.0 if percussive else 2.5
    available=min(len(x)-onset,round(duration*SR))
    x=x[onset:onset+available]
    # Fade the end of the available reference before zero padding, avoiding false modes.
    if percussive:
        fade=min(round(.05*SR),len(x));x[-fade:]*=np.linspace(1,0,fade)**2
    x=np.pad(x,(0,max(0,round(duration*SR)-len(x))))
    times=np.arange(0,duration+DT*.5,DT)
    samples=np.arange(len(x))/SR
    padded=np.pad(x,(N//2,N))
    analysis_times=np.arange(.04,min(duration,.6),.04)
    spectra=np.array([np.fft.rfft(padded[round(t*SR):round(t*SR)+N]*window,FFT) for t in analysis_times])
    power=np.mean(abs(spectra)**2,axis=0)
    candidates=np.linspace(expected*.97,expected*1.03,901)
    scores=np.array([sum(np.interp(f*np.arange(1,9),frequencies,power)/np.sqrt(np.arange(1,9))) for f in candidates])
    fundamental=candidates[np.argmax(scores)]
    def refine(p):
        logs=np.log(np.maximum(power[p-1:p+2],1e-20));den=logs[0]-2*logs[1]+logs[2]
        delta=0 if abs(den)<1e-15 else .5*(logs[0]-logs[2])/den
        return (p+np.clip(delta,-.5,.5))*SR/FFT
    if instrument=='tabla':
        region=np.flatnonzero(abs(frequencies-expected)<expected*.1)
        fundamental=refine(region[np.argmax(power[region])])
    if percussive:
        peaks,_=find_peaks(power,distance=max(2,round(18/(SR/FFT))))
        peaks=[p for p in peaks if fundamental*.85<frequencies[p]<17000 and power[p]>max(power)*1e-6]
        peaks=sorted(peaks,key=lambda p:power[p],reverse=True)[:100 if instrument=='sitar' else 48]
        mode_freqs=[refine(p) for p in peaks]
        mode_freqs=[f for f in mode_freqs if abs(f-fundamental)>18]+[fundamental]
    else:
        mode_freqs=[]
        for h in range(1,min(80,int(18000/fundamental))+1):
            region=np.flatnonzero(abs(frequencies-fundamental*h)<min(fundamental*.3, max(18,h*fundamental*.015)))
            mode_freqs.append(refine(region[np.argmax(power[region])]))
    mode_freqs=sorted(mode_freqs)
    analytic=hilbert(x)
    residual=x.copy()
    modes=[]
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
        ratio=freq/fundamental if percussive else round(freq/fundamental)
        mode={'ratio':round(float(ratio),7),'phase':round(float(phase%(2*np.pi)),7),
              'amplitudes':np.round(levels,7).tolist()}
        if percussive and abs(ratio-1)>0.01:
            # Preserve the measured evolution of upper resonances (jawari/strike),
            # while leaving the fundamental locked to the requested training pitch.
            angles=np.unwrap(np.angle(base))+np.pi/2
            reliable=amplitude>max(amplitude)*.015
            if reliable.sum()>2:
                angles=np.interp(samples,samples[reliable],angles[reliable])
            mode['phases']=np.round(np.interp(times,samples,angles),7).tolist()
        modes.append(mode)
    # Analyze the residual AFTER subtracting modes; don't turn harmonic leakage into hiss.
    noise_n=1024;noise_fft=2048;noise_window=np.hanning(noise_n)
    residual=np.pad(residual,(noise_n//2,noise_n))
    residual_spectrum=np.array([abs(np.fft.rfft(residual[round(t*SR):round(t*SR)+noise_n]*noise_window,noise_fft))**2 for t in times])
    noise_freq=np.fft.rfftfreq(noise_fft,1/SR)
    noise=[]
    for lo,hi in zip(NOISE_EDGES,NOISE_EDGES[1:]):
        mask=(noise_freq>=lo)&(noise_freq<hi)
        rms=np.sqrt(residual_spectrum[:,mask].sum(axis=1)*2/(noise_fft*np.sum(noise_window**2)))
        noise.append(np.round(rms,7).tolist())
    models.append(dict(instrument=instrument,midi=note,referenceHz=round(float(fundamental),6),stepSeconds=DT,
                       modes=modes,noise=noise,noiseEdges=NOISE_EDGES,referenceFile=filename,
                       sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    print(f'{instrument} {note}: {fundamental:.3f} Hz, {len(modes)} modes, {len(times)} frames',flush=True)
args.output.write_text(json.dumps({'version':2,'models':models},separators=(',',':'))+'\n')
print(f'{len(models)} profiles, {args.output.stat().st_size/1048576:.2f} MiB',flush=True)
