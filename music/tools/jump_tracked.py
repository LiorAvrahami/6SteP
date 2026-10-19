"""Beat-TRACKED structure map for real (non-MIDI) recordings.
Usage: python -P tools/jump_tracked.py <audio> <approx_bpm> [downbeat_phase 0-3 or 'auto'] [out_beats.json]
Prints tempo stability, per-bar energy, and best backward jumps that keep the bar phase. Saves tracked beat times."""
import sys, json, numpy as np, librosa
path, bpm = sys.argv[1], float(sys.argv[2]); phase_arg = sys.argv[3] if len(sys.argv)>3 else 'auto'; out = sys.argv[4] if len(sys.argv)>4 else None
y, sr = librosa.load(path, sr=22050, mono=True); dur=len(y)/sr; hop=256
on = librosa.onset.onset_strength(y=y, sr=sr, hop_length=hop)
t_est = float(np.atleast_1d(librosa.feature.tempo(onset_envelope=librosa.onset.onset_strength(y=y[:sr*60],sr=sr,hop_length=512), sr=sr, hop_length=512, start_bpm=bpm, max_tempo=260))[0])
_, beats = librosa.beat.beat_track(onset_envelope=on, sr=sr, hop_length=hop, bpm=t_est, tightness=100, units='time')
nb=len(beats); iv=np.diff(beats)
k=np.arange(nb); a,b=np.polyfit(k,beats,1); res=beats-(a*k+b)
print(f"{path}\n  dur {dur:.1f}s, {nb} beats, tempo est {t_est:.1f}, mean {60/iv.mean():.2f} bpm, first-half {60/iv[:nb//2].mean():.2f} / second-half {60/iv[nb//2:].mean():.2f}, interval std {iv.std()*1000:.0f} ms, linear-fit residual max {abs(res).max()*1000:.0f} ms (drift vs constant grid)")
ot=librosa.frames_to_time(np.arange(len(on)),sr=sr,hop_length=hop)
def ons_at(t): return float(np.interp(t,ot,on))
rms=librosa.feature.rms(y=y,hop_length=hop)[0]
chroma=librosa.feature.chroma_cqt(y=y,sr=sr,hop_length=hop); mf=librosa.feature.mfcc(y=y,sr=sr,hop_length=hop,n_mfcc=20)
fr=librosa.time_to_frames(beats,sr=sr,hop_length=hop); fr=np.append(fr,len(rms))
F=[]
for i in range(nb):
    s,e=fr[i],max(fr[i+1],fr[i]+1); c=chroma[:,s:e].mean(1); m=mf[1:,s:e].mean(1)
    F.append((c/(np.linalg.norm(c)+1e-9), m/(np.linalg.norm(m)+1e-9), float(rms[s:e].mean())))
if phase_arg=='auto':
    sc=[np.mean([ons_at(t)+2*F[i][2] for i,t in enumerate(beats) if i%4==p]) for p in range(4)]
    ph=int(np.argmax(sc)); print("  downbeat phase scores:",np.round(sc,2),"-> phase",ph)
else: ph=int(phase_arg)
print("  per-bar: bar# start  rms  (4 beats)")
for bar in range((nb-ph)//4):
    i0=ph+4*bar; e=[F[i][2] for i in range(i0,i0+4)]
    print(f"  bar{bar:3d} {beats[i0]:7.2f}  " + " ".join(f"{v:.3f}" for v in e), end="\n" if bar%2 else "   |   ")
print()
def dist(i,j):
    ci,mi,ei=F[i]; cj,mj,ej=F[j]; return float((1-ci@cj)+0.5*(1-mi@mj)+0.3*abs(np.log((ei+1e-4)/(ej+1e-4))))
nat=[dist(i,i+1) for i in range(nb-1)]; print(f"  natural step median {np.median(nat):.3f}")
res=[]
for i in range(8,nb-9):
    for j in range(ph,i-15):
        if (i+1-j)%4!=0: continue
        L=i+1-j
        if L%32!=0 and L%16!=0: continue   # 4- or 8-bar multiples only
        d=np.mean([dist(i+1+q,j+q) for q in range(8)])
        res.append((d,i,j,L))
res.sort()
print("  best backward jumps (score, after beat i [bar], -> beat j [bar], loop bars):")
for d,i,j,L in res[:20]: print(f"   {d:.3f}  after beat {i:3d} [bar {(i-ph)//4:2d} pos {(i-ph)%4}] -> beat {j:3d} [bar {(j-ph)//4:2d}]  {L//4} bars")
if out: json.dump({"beats":[float(t) for t in beats],"phase":ph,"sr_analysis":sr}, open(out,'w')); print("  saved",out)
