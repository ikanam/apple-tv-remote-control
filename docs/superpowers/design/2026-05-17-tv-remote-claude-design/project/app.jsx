// App shell: two phone scenarios side by side
const { useState: useState_A } = React;

const DEFAULT_DEVICE = { id: 'lr', name: '客厅', model: 'Apple TV 4K (3rd gen)' };

function PhoneFrame({ label, children }) {
  return (
    <div className="device-wrap">
      <div style={{
        width: 380, height: 800,
        borderRadius: 44,
        background: '#1a1c20',
        border: '10px solid #1a1c20',
        boxShadow:
          '0 0 0 1.5px #2c2e34, 0 40px 80px -20px rgba(0,0,0,0.7), 0 0 60px rgba(91,137,255,0.04)',
        overflow: 'hidden',
        position: 'relative',
        display: 'flex', flexDirection: 'column',
      }}>
        <div style={{
          height: 38, padding: '0 20px',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          color: '#fff', fontSize: 13, fontWeight: 600,
          fontFamily: 'Inter, system-ui',
          background: 'transparent', zIndex: 5, position: 'relative',
        }}>
          <span>9:41</span>
          <div style={{
            position: 'absolute', left: '50%', top: 8, transform: 'translateX(-50%)',
            width: 22, height: 22, borderRadius: '50%', background: '#000',
          }}/>
          <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <svg width="14" height="10" viewBox="0 0 14 10" fill="none">
              <path d="M1 9a6 6 0 0112 0M3 7a4 4 0 018 0M5 5a2 2 0 014 0" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/>
            </svg>
            <svg width="20" height="10" viewBox="0 0 20 10" fill="none">
              <rect x="0.5" y="1" width="16" height="8" rx="2" stroke="currentColor" strokeWidth="1.2"/>
              <rect x="2" y="2.5" width="11" height="5" rx="0.6" fill="currentColor"/>
              <rect x="17.5" y="3.5" width="1.5" height="3" rx="0.5" fill="currentColor"/>
            </svg>
          </div>
        </div>
        <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
          {children}
        </div>
        <div style={{ height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ width: 120, height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.3)' }}/>
        </div>
      </div>
      <div className="device-label">{label}</div>
    </div>
  );
}

// Scenario A: first launch — no auto-pair available, show connect screen
function ScenarioFirstLaunch() {
  const [paired, setPaired] = useState_A(null);
  if (!paired) return <ConnectScreen onConnected={(d) => setPaired(d)} />;
  return (
    <RemoteScreen
      device={paired}
      onSwitchDevice={() => setPaired(null)}
    />
  );
}

// Scenario B: normal use — already paired, remote shown; device switcher opens overlay
function ScenarioNormal() {
  const [device, setDevice] = useState_A(DEFAULT_DEVICE);
  const [picking, setPicking] = useState_A(false);
  return (
    <div style={{ height: '100%', position: 'relative', overflow: 'hidden' }}>
      <RemoteScreen
        device={device}
        onSwitchDevice={() => setPicking(true)}
      />
      {picking && (
        <div style={{
          position: 'absolute', inset: 0,
          background: '#0e1014', zIndex: 40,
          animation: 'slideUp .25s cubic-bezier(.2,.7,.2,1)',
        }}>
          <style>{`@keyframes slideUp { from {transform:translateY(40px);opacity:0} to {transform:none;opacity:1} }`}</style>
          <ConnectScreen
            currentId={device.id}
            onConnected={(d) => { setDevice(d); setPicking(false); }}
            onClose={() => setPicking(false)}
          />
        </div>
      )}
    </div>
  );
}

function App() {
  return (
    <div className="stage">
      <PhoneFrame label="首次启动 · 无已配对设备">
        <ScenarioFirstLaunch />
      </PhoneFrame>
      <PhoneFrame label="正常使用 · 自动连接 + 切换设备">
        <ScenarioNormal />
      </PhoneFrame>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
