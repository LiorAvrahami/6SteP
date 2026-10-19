import sys, numpy as np, librosa, soundfile as sf
path,bpm=sys.argv[1],float(sys.argv[2]); seams=[float(x) for x in sys.argv[3].split(',')] if len(sys.argv)>3 else []
y,sr=sf.read(path); m=y.mean(1)
# global tempo fit via beat tracking
on=librosa.onset.onset_strength(y=m,sr=sr); tempo,b=librosa.beat.beat_track(onset_envelope=on,sr=sr,start_bpm=bpm,tightness=400,units='time')
k=np.arange(len(b)); a,c=np.polyfit(k,b,1); r=b-(a*k+c)
print(f"{path}\n  dur {len(m)/sr:.1f}s  fitted tempo {60/a:.3f} bpm over {len(b)} beats; phase residual std {r.std()*1000:.1f} ms, max {abs(r).max()*1000:.0f} ms; drift first->last {(r[-1]-r[0])*1000:+.0f} ms")
# click check: max abs first-difference in +-30ms around each seam vs global 99.99th percentile
d=np.abs(np.diff(m)); thr=np.percentile(d,99.99)
worst=0
for t in seams:
    i=int(t*sr); w=d[i-int(0.03*sr):i+int(0.03*sr)]; worst=max(worst,w.max()/thr)
print(f"  seams checked {len(seams)}: worst seam jump = {worst:.2f} x the track's own 99.99th-percentile jump (>1.5 would suggest a click)")
