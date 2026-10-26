"""Original, deterministic instrumental score. No samples or external audio.
Requires numpy. Writes a 45-second, stereo 48 kHz PCM WAV.
"""
from pathlib import Path
import wave
import numpy as np

SR = 48000
DURATION = 45
rng = np.random.default_rng(20261009)
mix = np.zeros((SR * DURATION, 2), dtype=np.float64)
beat = 60 / 112

def add(signal, start, gain=1.0, pan=0.0):
    a = max(0, round(start * SR))
    b = min(len(mix), a + len(signal))
    if b <= a:
        return
    signal = signal[:b-a] * gain
    mix[a:b, 0] += signal * np.sqrt((1 - pan) / 2)
    mix[a:b, 1] += signal * np.sqrt((1 + pan) / 2)

def freq(note):
    return 440 * 2 ** ((note - 69) / 12)

def tone(note, duration, soft=False):
    t = np.arange(round(duration*SR)) / SR
    hz = freq(note)
    if soft:
        sound = np.sin(2*np.pi*hz*t) + .22*np.sin(2*np.pi*hz*2*t)
        env = np.minimum(t/.7, 1) * np.minimum((duration-t)/.8, 1) * .5
    else:
        sound = np.sin(2*np.pi*hz*t) + .32*np.sin(2*np.pi*hz*2*t) + .12*np.sin(2*np.pi*hz*3*t)
        env = (1-np.exp(-t*100)) * np.exp(-t*3.6) * np.minimum((duration-t)/.1, 1)
    return sound * env

chords = [[50,57,62,65,69], [46,53,58,62,65], [53,60,65,69,72], [48,55,60,64,67]]
for bar in range(21):
    start = bar * beat * 4
    notes = chords[(bar//2)%4]
    for j, note in enumerate(notes):
        add(tone(note, beat*4+1, True), start, .095, (j-2)*.25)
    for step in range(8):
        time = start + step*beat/2
        add(tone(notes[[0,2,4,2,1,3,4,3][step]]+12, 1.25), time, .115 if time>4 else .06, np.sin(step)*.55)
        # Quiet stereo delay, produced from the same synthesized note.
        add(tone(notes[[0,2,4,2,1,3,4,3][step]]+12, .8), time+beat*.75, .033, -np.sin(step)*.55)
    add(tone(notes[0]-12, beat*2), start, .12)

for i in range(int(DURATION/beat)):
    time = i*beat
    if 4 < time < 42:
        t=np.arange(int(.3*SR))/SR
        phase=2*np.pi*(43*t+72*.035*(1-np.exp(-t/.035)))
        add(np.sin(phase)*np.exp(-t*19)*(1-np.exp(-t*220)),time,.28)
        if i%2:
            t=np.arange(int(.16*SR))/SR
            noise=rng.normal(0,1,len(t))
            noise=np.concatenate([[0],np.diff(noise)])*.35
            add(noise*np.exp(-t*35),time,.075)
        t=np.arange(int(.055*SR))/SR
        noise=rng.normal(0,1,len(t))
        add(np.concatenate([[0],np.diff(noise)])*np.exp(-t*85),time+beat/2,.023,.3)

for time in [0,4.6,11.2,18.8,27.4,35]:
    for j,note in enumerate([74,81,86]):
        add(tone(note,2.4),time+j*.085,.105,(j-1)*.4)

# Gentle final chord, fade-in and two-second fade-out.
for j,note in enumerate([50,57,62,65,69,74]):
    add(tone(note,4.0,True),41,.16,(j-2.5)*.22)
t=np.arange(len(mix))/SR
fade=np.minimum(t/1.2,1)*np.minimum(np.maximum((DURATION-t)/2.2,0),1)
mix=np.tanh(mix*1.2)*fade[:,None]
mix*=.79/np.max(np.abs(mix))
pcm=np.clip(mix*32767,-32768,32767).astype('<i2')
output=Path(__file__).resolve().parents[1]/'public'/'trove-original.wav'
with wave.open(str(output),'wb') as wav:
    wav.setnchannels(2)
    wav.setsampwidth(2)
    wav.setframerate(SR)
    wav.writeframes(pcm.tobytes())
print(f'Wrote {output}: {DURATION}s original score')
