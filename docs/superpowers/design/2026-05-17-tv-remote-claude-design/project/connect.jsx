// Connection / device discovery screen
const { useState, useEffect, useRef } = React;

const MOCK_DEVICES = [
  { id: 'lr',  name: '客厅', model: 'Apple TV 4K (3rd gen)',   ip: '192.168.1.21',  rssi: 88, room: 'Living Room' },
  { id: 'br',  name: '主卧', model: 'Apple TV HD',             ip: '192.168.1.34',  rssi: 64, room: 'Bedroom' },
  { id: 'st',  name: '书房', model: 'Apple TV 4K (2nd gen)',   ip: '192.168.1.47',  rssi: 41, room: 'Study' },
];

const RssiBars = ({ rssi }) => {
  const bars = rssi > 75 ? 4 : rssi > 55 ? 3 : rssi > 35 ? 2 : 1;
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 14 }}>
      {[1,2,3,4].map(i => (
        <div key={i} style={{
          width: 3, height: i*3 + 2, borderRadius: 1,
          background: i <= bars ? '#7ee3b8' : 'rgba(255,255,255,0.18)',
        }}/>
      ))}
    </div>
  );
};

function PairingSheet({ device, onClose, onPaired }) {
  const [code, setCode] = useState(['', '', '', '']);
  const refs = [useRef(), useRef(), useRef(), useRef()];

  useEffect(() => { refs[0].current && refs[0].current.focus(); }, []);

  const onKey = (i, v) => {
    const next = [...code];
    next[i] = v.slice(-1).replace(/[^0-9]/g, '');
    setCode(next);
    if (next[i] && i < 3) refs[i+1].current.focus();
    if (next.every(x => x !== '')) {
      setTimeout(() => onPaired(device), 350);
    }
  };

  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: 'rgba(8,9,12,0.72)',
      backdropFilter: 'blur(14px)',
      display: 'flex', alignItems: 'flex-end',
      zIndex: 50,
      animation: 'fade .25s ease',
    }}>
      <style>{`
        @keyframes fade { from {opacity:0} to {opacity:1} }
        @keyframes slup { from {transform:translateY(40px);opacity:0} to {transform:none;opacity:1} }
      `}</style>
      <div style={{
        background: '#16181d',
        width: '100%',
        borderRadius: '24px 24px 0 0',
        padding: '20px 24px 32px',
        animation: 'slup .3s cubic-bezier(.2,.7,.2,1)',
        border: '1px solid rgba(255,255,255,0.06)',
        borderBottom: 'none',
      }}>
        <div style={{ width: 40, height: 4, background: 'rgba(255,255,255,0.15)', borderRadius: 2, margin: '0 auto 18px' }}/>
        <div style={{ fontSize: 11, letterSpacing: '0.18em', color: '#8fb8ff', fontFamily: 'JetBrains Mono, monospace', textTransform: 'uppercase' }}>
          配对设备
        </div>
        <div style={{ fontSize: 22, fontWeight: 600, color: '#fff', marginTop: 6 }}>
          {device.name}
        </div>
        <div style={{ fontSize: 13, color: 'rgba(255,255,255,0.55)', marginTop: 4 }}>
          请输入电视屏幕上显示的 4 位配对码
        </div>

        <div style={{ display: 'flex', gap: 10, marginTop: 22, marginBottom: 24 }}>
          {code.map((v, i) => (
            <input
              key={i} ref={refs[i]}
              value={v}
              onChange={(e) => onKey(i, e.target.value)}
              inputMode="numeric"
              maxLength={1}
              style={{
                flex: 1, height: 64,
                background: '#0e1014',
                border: `1.5px solid ${v ? '#5b89ff' : 'rgba(255,255,255,0.10)'}`,
                borderRadius: 14,
                textAlign: 'center',
                fontSize: 28, fontWeight: 600,
                color: '#fff',
                outline: 'none',
                fontFamily: 'JetBrains Mono, monospace',
                transition: 'border-color .15s',
              }}
            />
          ))}
        </div>

        <button onClick={onClose} style={{
          width: '100%', height: 48,
          background: 'transparent',
          border: '1px solid rgba(255,255,255,0.12)',
          borderRadius: 12,
          color: 'rgba(255,255,255,0.7)',
          fontSize: 14, fontWeight: 500,
          cursor: 'pointer',
        }}>取消</button>
      </div>
    </div>
  );
}

function ConnectScreen({ onConnected, onClose, currentId }) {
  const [scanning, setScanning] = useState(true);
  const [devices, setDevices] = useState([]);
  const [pairing, setPairing] = useState(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    setDevices([]); setScanning(true);
    const t1 = setTimeout(() => setDevices([MOCK_DEVICES[0]]), 600);
    const t2 = setTimeout(() => setDevices([MOCK_DEVICES[0], MOCK_DEVICES[1]]), 1200);
    const t3 = setTimeout(() => { setDevices(MOCK_DEVICES); setScanning(false); }, 2000);
    return () => { clearTimeout(t1); clearTimeout(t2); clearTimeout(t3); };
  }, [tick]);

  return (
    <div className="screen" style={{
      height: '100%', overflow: 'auto',
      background: '#0e1014',
      color: '#fff',
      padding: '8px 0 24px',
    }}>
      {/* Top bar */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {onClose ? (
            <button onClick={onClose} style={{
              background: 'transparent', border: 'none', cursor: 'pointer',
              width: 36, height: 36, borderRadius: 10,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: 'rgba(255,255,255,0.7)',
            }}>
              <IconBack size={20} />
            </button>
          ) : (
            <div style={{
              width: 32, height: 32, borderRadius: 10,
              background: 'linear-gradient(135deg, #4a72ff, #2c4ed4)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <IconTV size={18} stroke="#fff" sw={2}/>
            </div>
          )}
          <div style={{ fontWeight: 600, fontSize: 16 }}>
            {onClose ? '切换设备' : 'TV Remote'}
          </div>
        </div>
        <button style={{
          background: 'transparent', border: 'none',
          width: 40, height: 40, borderRadius: '50%',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: 'rgba(255,255,255,0.6)', cursor: 'pointer',
        }}>
          <IconSettings size={20} />
        </button>
      </div>

      {/* Hero */}
      <div style={{ padding: '24px 24px 8px' }}>
        <div style={{ fontSize: 11, color: '#8fb8ff', letterSpacing: '0.2em', fontFamily: 'JetBrains Mono, monospace' }}>
          {onClose ? 'SWITCH — SELECT DEVICE' : 'STEP 01 — DISCOVER'}
        </div>
        <div style={{ fontSize: 30, fontWeight: 700, lineHeight: 1.15, marginTop: 8 }}>
          {onClose ? (
            <>选择要控制的<br/><span style={{ color: 'rgba(255,255,255,0.55)' }}>Apple TV 设备</span></>
          ) : (
            <>在 Wi-Fi 网络上<br/><span style={{ color: 'rgba(255,255,255,0.55)' }}>寻找你的 Apple TV</span></>
          )}
        </div>
      </div>

      {/* Status pill */}
      <div style={{ padding: '20px 24px 12px' }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 12,
          padding: '12px 14px',
          background: 'rgba(91,137,255,0.08)',
          border: '1px solid rgba(91,137,255,0.18)',
          borderRadius: 12,
        }}>
          <IconWifi size={18} stroke="#8fb8ff" />
          <div style={{ flex: 1, fontSize: 13 }}>
            <div style={{ color: '#cfdaff' }}>HomeNet_5G</div>
            <div style={{ color: 'rgba(255,255,255,0.45)', fontSize: 11, marginTop: 2 }}>192.168.1.x · 已连接</div>
          </div>
          {scanning
            ? <IconSpinner size={18} color="#8fb8ff" />
            : <button onClick={() => setTick(t=>t+1)} style={{
                background: 'transparent', border: 'none', cursor: 'pointer',
                color: '#8fb8ff', display: 'flex', alignItems: 'center', padding: 4,
              }}><IconRefresh size={18} /></button>
          }
        </div>
      </div>

      {/* Devices list */}
      <div style={{ padding: '12px 16px' }}>
        <div style={{ fontSize: 11, letterSpacing: '0.18em', color: 'rgba(255,255,255,0.4)', padding: '8px 8px 12px', fontFamily: 'JetBrains Mono, monospace' }}>
          {scanning ? `搜索中... 已发现 ${devices.length}` : `已发现 ${devices.length} 台设备`}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {devices.map(d => {
            const isCurrent = currentId === d.id;
            return (
            <button key={d.id}
              onClick={() => isCurrent ? onClose && onClose() : setPairing(d)}
              style={{
                width: '100%', textAlign: 'left',
                background: isCurrent ? 'rgba(91,137,255,0.08)' : '#16181d',
                border: `1px solid ${isCurrent ? 'rgba(91,137,255,0.35)' : 'rgba(255,255,255,0.06)'}`,
                borderRadius: 16,
                padding: '14px 16px',
                display: 'flex', alignItems: 'center', gap: 14,
                cursor: 'pointer',
                color: '#fff',
                transition: 'border-color .15s, background .15s',
              }}
              onMouseEnter={e => { if (!isCurrent) e.currentTarget.style.borderColor = 'rgba(91,137,255,0.3)'; }}
              onMouseLeave={e => { if (!isCurrent) e.currentTarget.style.borderColor = 'rgba(255,255,255,0.06)'; }}
            >
              <div style={{
                width: 44, height: 44, borderRadius: 12,
                background: '#0e1014',
                border: '1px solid rgba(255,255,255,0.08)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: isCurrent ? '#8fb8ff' : '#cfdaff',
              }}>
                <IconTV size={22} />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{ fontWeight: 600, fontSize: 15 }}>{d.name}</div>
                  <IconDot size={6} color="#3ecf8e" />
                  {isCurrent && (
                    <span style={{
                      fontSize: 9, letterSpacing: '0.16em', color: '#8fb8ff',
                      fontFamily: 'JetBrains Mono, monospace',
                      background: 'rgba(91,137,255,0.12)',
                      padding: '2px 6px', borderRadius: 4,
                    }}>CURRENT</span>
                  )}
                </div>
                <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.5)', marginTop: 2 }}>
                  {d.model}
                </div>
                <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', marginTop: 1, fontFamily: 'JetBrains Mono, monospace' }}>
                  {d.ip}
                </div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
                <RssiBars rssi={d.rssi} />
                {isCurrent ? <IconCheck size={16} stroke="#8fb8ff"/> : <IconChevron size={16} stroke="rgba(255,255,255,0.35)" />}
              </div>
            </button>
            );
          })}

          {scanning && devices.length < 3 && (
            <div style={{
              padding: '20px 16px',
              border: '1px dashed rgba(255,255,255,0.08)',
              borderRadius: 16,
              display: 'flex', alignItems: 'center', gap: 12,
              color: 'rgba(255,255,255,0.4)', fontSize: 13,
            }}>
              <IconSpinner size={16} color="#8fb8ff"/>
              在网络中扫描 Apple TV 设备…
            </div>
          )}
        </div>
      </div>

      {/* Manual add */}
      <div style={{ padding: '20px 24px 8px' }}>
        <button style={{
          width: '100%', height: 52,
          background: 'transparent',
          border: '1px solid rgba(255,255,255,0.12)',
          borderRadius: 14,
          color: 'rgba(255,255,255,0.75)',
          fontSize: 14, fontWeight: 500,
          cursor: 'pointer',
        }}>
          + 手动添加 IP 地址
        </button>
      </div>

      <div style={{
        padding: '20px 28px 0',
        fontSize: 11, color: 'rgba(255,255,255,0.35)',
        textAlign: 'center', lineHeight: 1.6,
      }}>
        请确保你的手机和 Apple TV 在同一 Wi-Fi 网络下，<br/>
        并在 Apple TV 上开启 "AirPlay 与 HomeKit"。
      </div>

      {pairing && (
        <PairingSheet
          device={pairing}
          onClose={() => setPairing(null)}
          onPaired={(d) => { setPairing(null); onConnected(d); }}
        />
      )}
    </div>
  );
}

window.ConnectScreen = ConnectScreen;
