import struct, sys

FMT10={'0x00':'10x','0x0e':'10x','0x3e':'10x','0x73':'10x','0x79':'10x','0x7a':'10x'}
def size_of(op):
    if op in (0x00,0x0e): return 1
    if 0x01<=op<=0x01: return 1
    if op in (0x04,0x07): return 1
    if 0x0a<=op<=0x0d: return 1
    if 0x0f<=op<=0x12: return 1
    if op in (0x1d,0x1e,0x21,0x27): return 1
    if 0x7b<=op<=0x8f: return 1
    if 0xb0<=op<=0xcf: return 1
    if 0x3e<=op<=0x43: return 1
    if op in (0x73,0x79,0x7a): return 1
    if 0xe3<=op<=0xf9: return 1
    if op in (0x02,0x05,0x08): return 2
    if op in (0x13,0x15,0x16,0x19): return 2
    if op in (0x1a,0x1c,0x1f,0x22,0x24 if False else 0xff,0xfe): return 2
    if op==0x20 or op==0x23: return 2
    if op==0x24: return 3
    if op==0x25: return 3
    if op==0x26: return 3
    if op==0x28: 
        return 1
    if op==0x29: return 2
    if op==0x2a: return 3
    if op in (0x2b,0x2c): return 3
    if 0x2d<=op<=0x31: return 2
    if 0x32<=op<=0x37: return 2
    if 0x38<=op<=0x3d: return 2
    if 0x44<=op<=0x51: return 2
    if 0x52<=op<=0x5f: return 2
    if 0x60<=op<=0x6d: return 2
    if 0x6e<=op<=0x72: return 3
    if 0x74<=op<=0x78: return 3
    if op==0x90 or 0x91<=op<=0xaf: return 2
    if 0xd0<=op<=0xd7: return 2
    if 0xd8<=op<=0xe2: return 2
    if op==0x03 or op==0x06 or op==0x09: return 3
    if op==0x14 or op==0x17 or op==0x1b: return 3
    if op==0x18: return 5
    if op==0xfa or op==0xfb: return 4
    if op==0xfc or op==0xfd: return 3
    return 2

MN={0x00:'nop',0x01:'move',0x02:'move/from16',0x03:'move/16',0x04:'move-wide',0x05:'move-wide/from16',0x06:'move-wide/16',
0x07:'move-object',0x08:'move-object/from16',0x09:'move-object/16',0x0a:'move-result',0x0b:'move-result-wide',0x0c:'move-result-object',
0x0d:'move-exception',0x0e:'return-void',0x0f:'return',0x10:'return-wide',0x11:'return-object',0x12:'const/4',0x13:'const/16',
0x14:'const',0x15:'const/high16',0x16:'const-wide/16',0x17:'const-wide/32',0x18:'const-wide',0x19:'const-wide/high16',
0x1a:'const-string',0x1b:'const-string/jumbo',0x1c:'const-class',0x1d:'monitor-enter',0x1e:'monitor-exit',0x1f:'check-cast',
0x20:'instance-of',0x21:'array-length',0x22:'new-instance',0x23:'new-array',0x24:'filled-new-array',0x25:'filled-new-array/range',
0x26:'fill-array-data',0x27:'throw',0x28:'goto',0x29:'goto/16',0x2a:'goto/32',0x2b:'packed-switch',0x2c:'sparse-switch',
0x2d:'cmpl-float',0x2e:'cmpg-float',0x2f:'cmpl-double',0x30:'cmpg-double',0x31:'cmp-long',
0x32:'if-eq',0x33:'if-ne',0x34:'if-lt',0x35:'if-ge',0x36:'if-gt',0x37:'if-le',
0x38:'if-eqz',0x39:'if-nez',0x3a:'if-ltz',0x3b:'if-gez',0x3c:'if-gtz',0x3d:'if-lez',
0x44:'aget',0x45:'aget-wide',0x46:'aget-object',0x47:'aget-boolean',0x48:'aget-byte',0x49:'aget-char',0x4a:'aget-short',
0x4b:'aput',0x4c:'aput-wide',0x4d:'aput-object',0x4e:'aput-boolean',0x4f:'aput-byte',0x50:'aput-char',0x51:'aput-short',
0x52:'iget',0x53:'iget-wide',0x54:'iget-object',0x55:'iget-boolean',0x56:'iget-byte',0x57:'iget-char',0x58:'iget-short',
0x59:'iput',0x5a:'iput-wide',0x5b:'iput-object',0x5c:'iput-boolean',0x5d:'iput-byte',0x5e:'iput-char',0x5f:'iput-short',
0x60:'sget',0x61:'sget-wide',0x62:'sget-object',0x63:'sget-boolean',0x64:'sget-byte',0x65:'sget-char',0x66:'sget-short',
0x67:'sput',0x68:'sput-wide',0x69:'sput-object',0x6a:'sput-boolean',0x6b:'sput-byte',0x6c:'sput-char',0x6d:'sput-short',
0x6e:'invoke-virtual',0x6f:'invoke-super',0x70:'invoke-direct',0x71:'invoke-static',0x72:'invoke-interface',
0x74:'invoke-virtual/range',0x75:'invoke-super/range',0x76:'invoke-direct/range',0x77:'invoke-static/range',0x78:'invoke-interface/range',
0x90:'add-int',0x91:'sub-int',0x92:'mul-int',0x93:'div-int',0x94:'rem-int',0x95:'and-int',0x96:'or-int',0x97:'xor-int',
0x98:'shl-int',0x99:'shr-int',0x9a:'ushr-int',0x9b:'add-long',0x9c:'sub-long',0x9d:'mul-long',0x9e:'div-long',0x9f:'rem-long',
0xa0:'and-long',0xa1:'or-long',0xa2:'xor-long',0xa3:'shl-long',0xa4:'shr-long',0xa5:'ushr-long',
0xab:'add-int/2addr',0xac:'sub-int/2addr',0xad:'mul-int/2addr',0xae:'div-int/2addr',0xaf:'rem-int/2addr',
0xb0:'add-int/lit16',0xd0:'add-int/lit16',0xd8:'add-int/lit8'}

def uleb(b,o):
    r=0;s=0
    while True:
        x=b[o];o+=1;r|=(x&0x7f)<<s
        if not x&0x80: break
        s+=7
    return r,o

def read_str(b,off):
    _,o=uleb(b,off); e=b.index(b'\x00',o); return b[o:e].decode('utf-8','replace')

def load(path):
    b=open(path,'rb').read()
    sid_size,sid_off=struct.unpack_from('<II',b,0x38)
    tid_size,tid_off=struct.unpack_from('<II',b,0x40)
    fid_size,fid_off=struct.unpack_from('<II',b,0x50)
    mid_size,mid_off=struct.unpack_from('<II',b,0x58)
    cds,cdo=struct.unpack_from('<II',b,0x60)
    soff=struct.unpack_from('<%dI'%sid_size,b,sid_off)
    strs=[read_str(b,o) for o in soff]
    T=[strs[struct.unpack_from('<I',b,tid_off+4*i)[0]] for i in range(tid_size)]
    F=[];M=[]
    for i in range(fid_size):
        c,t,n=struct.unpack_from('<HHI',b,fid_off+8*i); F.append((T[c],strs[n],T[t]))
    for i in range(mid_size):
        c,p,n=struct.unpack_from('<HHI',b,mid_off+8*i); M.append((T[c],strs[n]))
    return b,strs,T,F,M,(cds,cdo)

def find_method(path, cls, mname, mdesc=None):
    b,strs,T,F,M,(cds,cdo)=load(path)
    for i in range(cds):
        cidx=struct.unpack_from('<I',b,cdo+32*i)[0]
        cdat=struct.unpack_from('<I',b,cdo+32*i+24)[0]
        if T[cidx]!=cls or not cdat: continue
        o=cdat
        sf,o=uleb(b,o); inf,o=uleb(b,o); dm,o=uleb(b,o); vm,o=uleb(b,o)
        for _ in range(sf+inf): _,o=uleb(b,o); _,o=uleb(b,o)
        mi=0
        for cnt in (dm,vm):
            for _ in range(cnt):
                d,o=uleb(b,o); mi+=d; a,o=uleb(b,o); c,o=uleb(b,o)
                if mi<len(M) and M[mi][1]==mname and c:
                    yield b,strs,T,F,M,c

def dis(path, cls, mname):
    for b,strs,T,F,M,c in find_method(path,cls,mname):
        regs,ins,outs,tries,dbg,insns=struct.unpack_from('<HHHHII',b,c)
        body=b[c+16:c+16+insns*2]
        print(f"### {cls}.{mname} regs={regs} ins={ins} outs={outs} units={insns}")
        i=0
        while i < len(body)-1:
            unit=struct.unpack_from('<H',body,i)[0]; op=unit&0xff; hi=unit>>8
            n=size_of(op)
            name=MN.get(op, f'op_{op:#04x}')
            extra=''
            if op in (0x1a,0x1b,0x1c,0x1f,0x22,0xfe,0xff,0x20,0x23):
                if op==0x1b: idx=struct.unpack_from('<I',body,i+2)[0]
                else: idx=struct.unpack_from('<H',body,i+2)[0]
                if op in (0x1a,0x1b): extra=f" '{strs[idx]}'"
                elif op==0x1c: extra=f" {T[idx]}"
                elif op==0x22: extra=f" {T[idx]}"
                elif op in (0x1f,0x20): extra=f" {T[idx]}"
            elif 0x6e<=op<=0x72:
                idx=struct.unpack_from('<H',body,i+2)[0]
                regs2=struct.unpack_from('<H',body,i+4)[0]
                reglist=[(regs2>>12)&0xf,(regs2>>8)&0xf,(regs2>>4)&0xf,regs2&0xf,hi]
                used=reglist[:2+(regs2>>12)]
                extra=f" {M[idx][0]}.{M[idx][1]} regs={used}"
            elif 0x74<=op<=0x78:
                idx=struct.unpack_from('<H',body,i+2)[0]; first=struct.unpack_from('<H',body,i+4)[0]
                extra=f" {M[idx][0]}.{M[idx][1]} first=v{first} cnt={hi}"
            elif op in (0x52,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x5b,0x5c,0x5d,0x5e,0x5f,0x60,0x61,0x62,0x63,0x64,0x65,0x66,0x67,0x68,0x69,0x6a,0x6b,0x6c,0x6d):
                idx=struct.unpack_from('<H',body,i+2)[0]
                extra=f" {F[idx][0]}.{F[idx][1]} ({F[idx][2]})"
            elif 0x44<=op<=0x51:
                a=hi; u2=struct.unpack_from('<H',body,i+2)[0]
                bb=u2&0xff; cc=(u2>>8)&0xff
                extra=f" v{a}, v{bb}, v{cc}"
            elif op in (0x13,0x16):
                extra=f" v{hi}, #{struct.unpack_from('<h',body,i+2)[0]}"
            elif op==0x14:
                extra=f" v{hi}, #{struct.unpack_from('<i',body,i+2)[0]}"
            elif op==0x15:
                extra=f" v{hi}, #0x{struct.unpack_from('<H',body,i+2)[0]:04x}"
            elif op==0x12:
                v=hi&0xf; lit=hi>>4
                if lit>7: lit-=16
                extra=f" v{v}, #{lit}"
            elif op in (0x28,0x29,0x2a):
                if op==0x28:
                    off=struct.unpack_from('<b',body,i+1)[0]; extra=f" -> {i+off*2}"
                elif op==0x29:
                    off=struct.unpack_from('<h',body,i+2)[0]; extra=f" -> {i+off*2}"
                else:
                    off=struct.unpack_from('<i',body,i+2)[0]; extra=f" -> {i+off*2}"
            elif 0x32<=op<=0x37:
                r1=hi&0xf; r2=hi>>4; off=struct.unpack_from('<h',body,i+2)[0]
                extra=f" v{r1}, v{r2} -> {i+off*2}"
            elif 0x38<=op<=0x3d:
                off=struct.unpack_from('<h',body,i+2)[0]; extra=f" v{hi} -> {i+off*2}"
            print(f"  {i:4d}: {name}{extra}")
            i+=n*2
