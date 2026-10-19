"""Swing-ratio check. Usage: python -P tools/swingratio.py <audio> [approx_bpm]
Reports tempo and where the offbeat onsets fall inside the beat, measured from the beat peak:
0.50 = straight eighths, 0.60 = light shuffle, 0.67 = triplet swing, 0.75 = hard/dotted swing."""
import sys, numpy as np, librosa
path=sys.argv[1]; bpm=float(sys.argv[2]) if len(sys.argv)>2 else 140.0
y,sr=librosa.load(path,sr=22050,mono=True,duration=120); hop=128
on=librosa.onset.onset_strength(y=y,sr=sr,hop_length=hop)
# tempo prior from a short excerpt at a coarse hop (keeps memory ~10 MB instead of ~200 MB), then beat-track with that tempo fixed
_on_t=librosa.onset.onset_strength(y=y[:sr*40],sr=sr,hop_length=512)
_t=librosa.feature.tempo(onset_envelope=_on_t,sr=sr,hop_length=512,start_bpm=bpm,max_tempo=260)
_,beats=librosa.beat.beat_track(onset_envelope=on,sr=sr,hop_length=hop,bpm=float(np.atleast_1d(_t)[0]),tightness=300,units='time')
k=np.arange(len(beats)); a,b=np.polyfit(k,beats,1)
ons=librosa.onset.onset_detect(onset_envelope=on,sr=sr,hop_length=hop,units='time',backtrack=False)
ph=((ons-b)/a)%1.0
hist,_=np.histogram(ph,bins=24,range=(0,1))
beat_bin=int(np.argmax(hist)); beat_ph=(beat_bin+0.5)/24
rel=(ph-beat_ph)%1.0                    # position relative to the beat peak
off=rel[(rel>0.33)&(rel<0.85)]
n_beat=int(((rel<0.12)|(rel>0.88)).sum()); n_off=len(off)
offpos=float(np.median(off)) if n_off else float('nan')
feel=("straight" if offpos<0.55 else "light shuffle" if offpos<0.62 else "triplet swing" if offpos<0.72 else "hard swing") if n_off else "n/a"
print(f"{60/a:.1f}\t{offpos:.2f}\t{feel}\t{n_off}/{n_beat}\t{path}")
