"""Silently decode a few frames with Morrow's bundled libmpv (no window/audio)."""
import ctypes as c, json, os, sys, time
library_path, url = sys.argv[1:3]
os.add_dll_directory(os.path.dirname(library_path))
lib=c.CDLL(library_path)
lib.mpv_create.restype=c.c_void_p
lib.mpv_set_option_string.argtypes=[c.c_void_p,c.c_char_p,c.c_char_p]
lib.mpv_initialize.argtypes=[c.c_void_p]
lib.mpv_command.argtypes=[c.c_void_p,c.POINTER(c.c_char_p)]
lib.mpv_get_property.argtypes=[c.c_void_p,c.c_char_p,c.c_int,c.c_void_p]
lib.mpv_terminate_destroy.argtypes=[c.c_void_p]
class Event(c.Structure):
    _fields_=[('event_id',c.c_int),('error',c.c_int),('userdata',c.c_ulonglong),('data',c.c_void_p)]
lib.mpv_wait_event.argtypes=[c.c_void_p,c.c_double]
lib.mpv_wait_event.restype=c.POINTER(Event)
h=lib.mpv_create()
result={'decoded':False,'width':0,'height':0}
try:
    for key,value in {'vo':'null','ao':'null','mute':'yes','hwdec':'no','terminal':'no','really-quiet':'yes','network-timeout':'15','frames':'8','cache':'no'}.items():
        lib.mpv_set_option_string(h,key.encode(),value.encode())
    if lib.mpv_initialize(h)<0: raise RuntimeError('libmpv initialization failed')
    args=(c.c_char_p*4)(b'loadfile',url.encode(),b'replace',None)
    if lib.mpv_command(h,args)<0: raise RuntimeError('libmpv load failed')
    deadline=time.monotonic()+35
    while time.monotonic()<deadline:
        event=lib.mpv_wait_event(h,0.2).contents
        for key in ('width','height'):
            number=c.c_longlong()
            if lib.mpv_get_property(h,('video-params/'+('w' if key=='width' else 'h')).encode(),4,c.byref(number))>=0: result[key]=number.value
        position=c.c_double()
        if lib.mpv_get_property(h,b'time-pos',5,c.byref(position))>=0 and position.value>0 and result['width']>0:
            result['decoded']=True
            result['position']=round(position.value,3)
            break
        if event.event_id==7:
            result['endOfFile']=True
            break
finally:
    lib.mpv_terminate_destroy(h)
print(json.dumps(result))
sys.exit(0 if result['decoded'] else 1)
