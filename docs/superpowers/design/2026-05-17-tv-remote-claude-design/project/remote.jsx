// Main remote control screen
const { useState: useState_R, useRef: useRef_R, useEffect: useEffect_R } = React;

// Touchpad: 4 directional zones + center OK
function Touchpad({ onPress }) {
  const [active, setActive] = useState_R(null); // 'up'|'down'|'left'|'right'|'ok'
  const ref = useRef_R(null);

  const trigger = (dir) => {
    setActive(dir);
    onPress(dir);
    setTimeout(() => setActive(null), 180);
  };

  const handleTap = (e) => {
    const rect = ref.current.getBoundingClientRect();
    const cx = rect.width / 2;
    const cy = rect.height / 2;
    const x = e.clientX - rect.left - cx;
    const y = e.clientY - rect.top - cy;
    const r = Math.hypot(x, y);
    const innerR = rect.width * 0.18; // center OK radius

    let dir;
    if (r < innerR) dir = 'ok';
    else {
      const ang = Math.atan2(y, x) * 180 / Math.PI; // -180..180
      if (ang >= -45 && ang < 45)        dir = 'right';
      else if (ang >= 45 && ang < 135)   dir = 'down';
      else if (ang >= -135 && ang < -45) dir = 'up';
      else                                dir = 'left';
    }
    trigger(dir);
  };

  const wedgeStyle = (dir) => ({
    position: 'absolute', pointerEvents: 'none',
    transition: 'opacity .15s, transform .18s',
    opacity: active === dir ? 1 : 0,
  });

  return (
    <div style={{
      position: 'relative',
      width: 240, height: 240,
      margin: '0 auto',
    }}>
      {/* outer ring glow */}
      <div style={{
        position: 'absolute', inset: -10, borderRadius: '50%',
        background: 'radial-gradient(closest-side, rgba(91,137,255,0.18), transparent 70%)',
        pointerEvents: 'none',
        opacity: active ? 1 : 0.4,
        transition: 'opacity .3s',
      }}/>

      {/* base disk */}
      <div
        ref={ref}
        onClick={handleTap}
        style={{
          position: 'absolute', inset: 0, borderRadius: '50%',
          background: 'radial-gradient(circle at 35% 30%, #2a2e38 0%, #181a20 60%, #0f1115 100%)',
          boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.08), 0 20px 40px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.04)',
          cursor: 'pointer',
          userSelect: 'none',
        }}
      >
        {/* direction dots (subtle indicators) */}
        {['up','right','down','left'].map((d) => {
          const positions = {
            up:    { top: 14,    left: '50%', transform: 'translateX(-50%)' },
            right: { right: 14,  top: '50%',  transform: 'translateY(-50%)' },
            down:  { bottom: 14, left: '50%', transform: 'translateX(-50%)' },
            left:  { left: 14,   top: '50%',  transform: 'translateY(-50%)' },
          };
          return (
            <div key={d} style={{
              position: 'absolute',
              ...positions[d],
              width: 6, height: 6, borderRadius: '50%',
              background: active === d ? '#cfdaff' : 'rgba(255,255,255,0.4)',
              boxShadow: active === d ? '0 0 10px rgba(91,137,255,0.6)' : 'none',
              transition: 'background .15s, box-shadow .15s',
              pointerEvents: 'none',
            }}/>
          );
        })}

        {/* directional glow on press */}
        {['up','right','down','left'].map(d => {
          const grads = {
            up:    'radial-gradient(ellipse 80% 60% at 50% 0%,   rgba(91,137,255,0.55), transparent 60%)',
            right: 'radial-gradient(ellipse 60% 80% at 100% 50%, rgba(91,137,255,0.55), transparent 60%)',
            down:  'radial-gradient(ellipse 80% 60% at 50% 100%, rgba(91,137,255,0.55), transparent 60%)',
            left:  'radial-gradient(ellipse 60% 80% at 0% 50%,   rgba(91,137,255,0.55), transparent 60%)',
          };
          return (
            <div key={d} style={{
              position: 'absolute', inset: 0, borderRadius: '50%',
              background: grads[d],
              opacity: active === d ? 1 : 0,
              transition: 'opacity .18s',
              pointerEvents: 'none',
            }}/>
          );
        })}

        {/* center inner button */}
        <div style={{
          position: 'absolute',
          top: '50%', left: '50%',
          transform: `translate(-50%, -50%) scale(${active === 'ok' ? 0.94 : 1})`,
          width: 96, height: 96, borderRadius: '50%',
          background: active === 'ok'
            ? 'radial-gradient(circle at 40% 35%, #2c3956, #1a2236)'
            : 'radial-gradient(circle at 40% 35%, #1f232b, #0d1015)',
          boxShadow: active === 'ok'
            ? 'inset 0 0 0 1px rgba(91,137,255,0.45), 0 0 24px rgba(91,137,255,0.25)'
            : 'inset 0 1px 0 rgba(255,255,255,0.05), inset 0 0 0 1px rgba(0,0,0,0.4), 0 6px 14px rgba(0,0,0,0.5)',
          transition: 'transform .12s, box-shadow .15s, background .15s',
          pointerEvents: 'none',
        }}/>
      </div>
    </div>
  );
}

// Generic round button
function RemoteButton({ children, label, onClick, size = 80, active }) {
  const [pressed, setPressed] = useState_R(false);
  return (
    <button
      onClick={() => { onClick && onClick(); }}
      onPointerDown={() => setPressed(true)}
      onPointerUp={() => setPressed(false)}
      onPointerLeave={() => setPressed(false)}
      style={{
        width: size, height: size, borderRadius: '50%',
        background: pressed || active
          ? 'radial-gradient(circle at 35% 30%, #2c3956, #1a2236)'
          : 'radial-gradient(circle at 35% 30%, #232730, #14171d)',
        border: 'none',
        color: pressed || active ? '#cfdaff' : 'rgba(255,255,255,0.78)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        cursor: 'pointer',
        boxShadow: pressed || active
          ? 'inset 0 0 0 1px rgba(91,137,255,0.4), 0 0 18px rgba(91,137,255,0.2)'
          : 'inset 0 1px 0 rgba(255,255,255,0.06), inset 0 0 0 1px rgba(255,255,255,0.04), 0 6px 14px rgba(0,0,0,0.4)',
        transform: pressed ? 'scale(0.95)' : 'scale(1)',
        transition: 'transform .1s, box-shadow .15s, background .15s, color .15s',
        position: 'relative',
      }}
    >
      {children}
      {label && (
        <span style={{
          position: 'absolute', bottom: -22, left: '50%', transform: 'translateX(-50%)',
          fontSize: 10, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.1em',
          fontFamily: 'JetBrains Mono, monospace', textTransform: 'uppercase',
          whiteSpace: 'nowrap',
        }}>{label}</span>
      )}
    </button>
  );
}

// Volume column (single tall pill, + on top, - on bottom)
function VolumePill({ onUp, onDown }) {
  const [pUp, setPUp] = useState_R(false);
  const [pDn, setPDn] = useState_R(false);

  const half = (pressed, setP, onTap, IconCmp) => (
    <button
      onClick={onTap}
      onPointerDown={() => setP(true)}
      onPointerUp={() => setP(false)}
      onPointerLeave={() => setP(false)}
      style={{
        flex: 1,
        background: pressed ? 'rgba(91,137,255,0.10)' : 'transparent',
        border: 'none',
        color: pressed ? '#cfdaff' : 'rgba(255,255,255,0.78)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        cursor: 'pointer',
        transition: 'background .15s, color .15s',
      }}
    >
      <IconCmp size={22} sw={2} />
    </button>
  );

  return (
    <div style={{
      width: 80, height: 172,
      borderRadius: 40,
      background: 'radial-gradient(circle at 35% 20%, #232730, #14171d)',
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.06), inset 0 0 0 1px rgba(255,255,255,0.04), 0 6px 14px rgba(0,0,0,0.4)',
      display: 'flex', flexDirection: 'column',
      overflow: 'hidden',
    }}>
      {half(pUp, setPUp, onUp, IconPlus)}
      <div style={{ height: 1, background: 'rgba(255,255,255,0.05)' }}/>
      {half(pDn, setPDn, onDown, IconMinus)}
    </div>
  );
}

// Tiny toast for command feedback
function Toast({ label }) {
  if (!label) return null;
  return (
    <div style={{
      position: 'absolute', top: 88, left: '50%', transform: 'translateX(-50%)',
      background: 'rgba(20,22,28,0.92)',
      border: '1px solid rgba(255,255,255,0.08)',
      color: '#cfdaff',
      padding: '8px 14px', borderRadius: 100,
      fontSize: 12, letterSpacing: '0.12em',
      fontFamily: 'JetBrains Mono, monospace', textTransform: 'uppercase',
      boxShadow: '0 12px 30px rgba(0,0,0,0.4)',
      zIndex: 30,
      animation: 'toastIn .2s ease',
      pointerEvents: 'none',
    }}>
      <style>{`@keyframes toastIn { from {opacity:0;transform:translate(-50%,-6px)} to {opacity:1;transform:translate(-50%,0)} }`}</style>
      {label}
    </div>
  );
}

function KeyboardOverlay({ onClose, onType }) {
  const [text, setText] = useState_R('');
  const ref = useRef_R();
  useEffect_R(() => { ref.current && ref.current.focus(); }, []);
  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'rgba(8,9,12,0.85)',
      backdropFilter: 'blur(14px)',
      zIndex: 50,
      display: 'flex', flexDirection: 'column',
      padding: 20,
      animation: 'fade .2s ease',
    }}>
      <style>{`@keyframes fade { from {opacity:0} to {opacity:1} }`}</style>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 11, letterSpacing: '0.2em', color: '#8fb8ff', fontFamily: 'JetBrains Mono, monospace', textTransform: 'uppercase' }}>
          TEXT INPUT → 客厅
        </div>
        <button onClick={onClose} style={{
          background: 'transparent', border: '1px solid rgba(255,255,255,0.12)',
          color: 'rgba(255,255,255,0.7)', padding: '6px 12px', borderRadius: 100,
          fontSize: 12, cursor: 'pointer',
        }}>完成</button>
      </div>
      <div style={{
        background: '#0e1014', border: '1px solid rgba(91,137,255,0.3)',
        borderRadius: 14, padding: 16, minHeight: 120,
      }}>
        <input
          ref={ref}
          value={text}
          onChange={(e) => { setText(e.target.value); onType && onType(e.target.value); }}
          placeholder="在此输入要发送到电视的文本..."
          style={{
            width: '100%', background: 'transparent', border: 'none',
            color: '#fff', fontSize: 18, outline: 'none',
          }}
        />
        <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', marginTop: 12, fontFamily: 'JetBrains Mono, monospace' }}>
          {text.length} chars · 实时发送到 Apple TV
        </div>
      </div>
    </div>
  );
}

function RemoteScreen({ device, onSwitchDevice }) {
  const [playing, setPlaying] = useState_R(true);
  const [showKb, setShowKb] = useState_R(false);

  const cmd = () => {}; // silent — no toast, just button visual feedback

  return (
    <div className="screen" style={{
      height: '100%', overflow: 'hidden', position: 'relative',
      background:
        'radial-gradient(120% 80% at 50% 0%, #181c25 0%, #0c0e13 60%, #08090c 100%)',
      color: '#fff',
      display: 'flex', flexDirection: 'column',
    }}>
      {/* Top: centered device switcher */}
      <div style={{ padding: '14px 16px 6px', display: 'flex', alignItems: 'center', position: 'relative', minHeight: 44 }}>
        <button onClick={onSwitchDevice} style={{
          position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%, -50%)',
          display: 'flex', alignItems: 'center', gap: 6,
          background: 'transparent', border: 'none',
          padding: '6px 10px', cursor: 'pointer',
          color: '#fff',
        }}>
          <span style={{ fontSize: 15, fontWeight: 600, whiteSpace: 'nowrap' }}>{device.name}</span>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.5)" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M6 9l6 6 6-6"/>
          </svg>
        </button>
        <div style={{ flex: 1 }}/>
        <button
          onClick={cmd}
          style={{
            background: 'rgba(255,255,255,0.06)',
            border: 'none',
            color: 'rgba(255,255,255,0.75)',
            width: 32, height: 32, borderRadius: '50%',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer', flexShrink: 0,
          }}>
          <IconPower size={16} />
        </button>
      </div>

      {/* Touchpad + buttons */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 28, padding: '12px 20px 24px' }}>
        <Touchpad onPress={(d) => cmd(d.toUpperCase())} />

        {/* 3-row × 2-col grid; volume pill spans rows 2-3 — columns at left/right edges */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gridTemplateRows: 'auto auto auto',
          rowGap: 12,
          columnGap: 0,
          width: '100%',
          padding: '0 12px',
          alignItems: 'center',
          justifyItems: 'center',
        }}>
          {/* Row 1 */}
          <RemoteButton onClick={cmd}>
            <IconBack size={20} sw={2} />
          </RemoteButton>
          <RemoteButton onClick={cmd}>
            <IconTV size={20} sw={2} />
          </RemoteButton>

          {/* Row 2 — play/pause combined glyph */}
          <RemoteButton onClick={cmd}>
            <IconPlayPause size={22}/>
          </RemoteButton>
          {/* Volume pill spans rows 2-3 */}
          <div style={{
            gridRow: '2 / span 2',
            gridColumn: 2,
            position: 'relative',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <VolumePill onUp={cmd} onDown={cmd} />
          </div>

          {/* Row 3: keyboard (replaces mute) */}
          <RemoteButton onClick={() => setShowKb(true)}>
            <IconKeyboard size={20} sw={1.8}/>
          </RemoteButton>
        </div>
      </div>

      {showKb && <KeyboardOverlay onClose={() => setShowKb(false)} onType={() => {}} />}
    </div>
  );
}

window.RemoteScreen = RemoteScreen;
