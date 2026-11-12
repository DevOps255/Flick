"""Draws the AirDrop effect of AirDropShader.kt on a screenshot, at eight moments, to judge it without a phone.

Use a portrait screenshot of the app. The numbers must be kept equal to the Kotlin and AGSL code by hand.
Run: python3 scripts/preview_airdrop.py screenshot.png preview.png
"""
import numpy as np, math
from PIL import Image
from scipy.ndimage import map_coordinates, gaussian_filter
import sys
if len(sys.argv) < 3:
    sys.exit('usage: preview_airdrop.py screenshot.png output.png')
B=np.asarray(Image.open(sys.argv[1]).convert('RGB'),dtype=np.float32)/255
H,W,_=B.shape; aspect=W/H
ys,xs=np.mgrid[0:H,0:W].astype(np.float32)
SX=(xs+0.5)/W; SY=1-(ys+0.5)/H           # y up
PX=(SX-0.5)*aspect
def sm(a,b,x):
    t=np.clip((x-a)/(b-a),0,1); return t*t*(3-2*t)
def samp(img,u,v):
    out=np.empty_like(img)
    for c in range(3): out[...,c]=map_coordinates(img[...,c],[(1-v)*H-0.5,np.clip(u,0,1)*W-0.5],order=1,mode='nearest')
    return out
Bb=gaussian_filter(B,(5,5,0))
RISE,ACC,STRIKE=1400,600,450
def timeline(t):
    rise_t=min(max(t/RISE,0),1)
    rise=1-(1-rise_t)**1.6
    energy=float(sm(0,1,np.float32(t/(RISE+ACC))))**0.75
    accumulate=float(sm(RISE*0.6,RISE+ACC,np.float32(t)))
    s=min(max((t-(RISE+ACC))/STRIKE,0),1)
    return rise,energy,accumulate,s**2.2
def render(t):
    rise,energy,accumulate,wave=timeline(t)
    far=math.sqrt((0.5*aspect)**2+1)
    R=wave*(far+0.45)
    d=np.hypot(PX,SY-1.0)
    behind=1-sm(R-0.30,R,d)               # 1 behind the front: effect is gone
    m=1-behind
    gy=-0.12+1.08*rise
    # pre strike bulge toward the glow
    g=np.exp(-(((SY-gy)/0.30)**2))
    v=SY+(gy-SY)*0.10*g*energy*m
    u=SX
    # ring distortion following the front
    ring=np.exp(-(((d-(R-0.10))/0.11)**2))*(wave>0)*(1-wave*0.3)
    nx=PX/np.maximum(d,1e-4); ny=(SY-1.0)/np.maximum(d,1e-4)
    disp=0.05*ring
    u=u-(nx*disp)/aspect
    v=v-(ny*disp)
    col=samp(B,u,v); bl=samp(Bb,u,v)
    blur=energy*(0.35+0.65*sm(0,1,SY))*m
    out=col+(bl-col)*blur[...,None]
    # glow light
    gcolor=np.stack([1.0+0*PX,0.45+0.2*sm(-0.4,0.4,PX),0.70+0.30*sm(-0.4,0.4,PX)],-1)
    gcolor[...,0]=1.0-0.55*sm(-0.4,0.4,PX)
    f=np.exp(-((PX/0.70)**2+((SY-gy)/0.45)**2))
    crest=np.exp(-(((SY-(gy+0.20))/0.05)**2))*np.exp(-((PX/0.8)**2))*0.30*(1-accumulate)*energy
    top=np.exp(-((PX/0.40)**2+((SY-1.0)/0.14)**2))*accumulate
    I=(f*energy*0.95+crest+top*1.2)*m
    a=np.clip(I,0,1)[...,None]*0.85
    out=out+(gcolor-out)*a
    out=out+ring[...,None]*0.18
    return np.clip(out,0,1)
if __name__=="__main__":
    ts=[250,550,850,1150,1450,1800,2150,2300]
    ims=[render(t) for t in ts]
    top=np.concatenate(ims[:4],1); bot=np.concatenate(ims[4:],1)
    Image.fromarray((np.concatenate([top,bot],0)*255).astype(np.uint8)).save(sys.argv[2])
