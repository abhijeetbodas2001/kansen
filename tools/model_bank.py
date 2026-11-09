"""Write the compact KSN4 parameter bank (no waveform audio)."""
import json
import struct
from pathlib import Path


def write_model_bank(models, output):
    output = Path(output)
    if output.suffix != '.bin':
        output.write_text(json.dumps({'version': 4, 'models': models}, separators=(',', ':')) + '\n')
        return
    with output.open('wb') as stream:
        def integer(value):
            stream.write(struct.pack('>i', value))

        def number(value):
            stream.write(struct.pack('>d', value))

        def array(values):
            integer(len(values))
            stream.write(struct.pack(f'>{len(values)}f', *values))

        stream.write(b'KSN4')
        integer(len(models))
        for model in models:
            name = model['instrument'].encode('ascii')
            stream.write(struct.pack('>H', len(name)))
            stream.write(name)
            integer(model['midi'])
            number(model['referenceHz'])
            number(model['stepSeconds'])
            integer(len(model['modes']))
            for mode in model['modes']:
                number(mode['ratio'])
                number(mode['phase'])
                number(mode.get('pitchTracking', 1.0))
                array(mode['amplitudes'])
                array(mode.get('phases', []))
            integer(len(model['noise']))
            for band in model['noise']:
                array(band)
            array(model['noiseEdges'])
    manifest = [{**{key: value for key, value in model.items()
                   if key not in ('modes', 'noise', 'noiseEdges')},
                 'modeCount': len(model['modes'])} for model in models]
    output.with_name('sources.json').write_text(json.dumps({'version': 4, 'models': manifest}, indent=2) + '\n')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    write_model_bank(json.loads(args.input.read_text())['models'], args.output)
