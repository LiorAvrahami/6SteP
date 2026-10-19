"""Splice a track on its beat grid. Plan JSON: {"bpm":..,"t0":..,"xf_ms":20,"segments":[[start_beat,end_beat_excl],...],"tail_s":optional seconds appended after last segment (ring-out)}"""
import sys, json, numpy as np, soundfile as sf, librosa
src, plan_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
plan=json.load(open(plan_path)); bpm,t0,xf_ms=plan['bpm'],plan['t0'],plan.get('xf_ms',20)
y, sr = librosa.load(src, sr=None, mono=False)   # (2, n)
if y.ndim==1: y=y[None,:]
n=y.shape[1]; beat=60/bpm; xf=int(round(xf_ms/1000*sr))
def t2s(bk): return int(round((t0+bk*beat)*sr))
w_out=np.cos(np.linspace(0,np.pi/2,xf))**2; w_in=1-w_out   # equal-power-ish (sum of squares=1 not needed for correlated audio; use linear-power)
w_out=np.sqrt(w_out); w_in=np.sqrt(w_in)
pieces=[]; seams=[]
for k,(a,b) in enumerate(plan['segments']):
    s=t2s(a); e=t2s(b) if b is not None else n
    if plan.get('tail_s') and k==len(plan['segments'])-1 and b is not None: e=min(n, e+int(plan['tail_s']*sr))
    seg=y[:, s:e].copy()
    if k>0:
        pre=y[:, s-xf:s]                       # incoming audio just before its downbeat
        prev=pieces[-1]
        prev[:, -xf:]=prev[:, -xf:]*w_out + pre*w_in   # blend over the last xf samples of outgoing piece
        seams.append((sum(p.shape[1] for p in pieces)/sr, a, plan['segments'][k-1][1]))
    pieces.append(seg)
mix=np.concatenate(pieces,axis=1)
if plan.get('wrap_xf'):   # make file itself a seamless loop: blend its tail with audio preceding first segment's downbeat
    s=t2s(plan['segments'][0][0]); pre=y[:, s-xf:s]; mix[:, -xf:]=mix[:, -xf:]*w_out+pre*w_in
if plan.get('fade_out_s'):
    fo=int(plan['fade_out_s']*sr); mix[:, -fo:]*=np.linspace(1,0,fo)
peak=np.abs(mix).max(); print(f"peak {peak:.3f}")
if peak>0.999: mix=mix/peak*0.999
sf.write(out, mix.T, sr, subtype='PCM_16')
print(f"wrote {out}: {mix.shape[1]/sr:.2f}s, {len(seams)} seams at", [f"{t:.2f}s(beat {prev_end}->{a})" for t,a,prev_end in seams])
