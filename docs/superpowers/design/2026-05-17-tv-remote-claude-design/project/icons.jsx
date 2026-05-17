// Shared SVG icons (stroke-based, minimal, original)
const Icon = ({ children, size = 24, stroke = 'currentColor', sw = 1.8, fill = 'none' }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={fill} stroke={stroke}
       strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round">
    {children}
  </svg>
);

const IconBack    = (p) => <Icon {...p}><path d="M15 5l-7 7 7 7" /></Icon>;
const IconChevron = (p) => <Icon {...p}><path d="M9 5l7 7-7 7" /></Icon>;
const IconTV      = (p) => <Icon {...p}><rect x="3" y="5" width="18" height="12" rx="2" /><path d="M8 21h8M12 17v4" /></Icon>;
const IconPlay    = (p) => <Icon {...p} fill="currentColor" stroke="none"><path d="M7 5.5v13l11-6.5z" /></Icon>;
const IconPlayPause = (p) => (
  <Icon {...p} fill="currentColor" stroke="none">
    <path d="M3 5.5v13l9-6.5z" />
    <rect x="14" y="5.5" width="2.6" height="13" rx="0.5"/>
    <rect x="18.4" y="5.5" width="2.6" height="13" rx="0.5"/>
  </Icon>
);
const IconPlus    = (p) => <Icon {...p}><path d="M12 6v12M6 12h12" /></Icon>;
const IconMinus   = (p) => <Icon {...p}><path d="M6 12h12" /></Icon>;
const IconKeyboard= (p) => (
  <Icon {...p}>
    <rect x="2.5" y="6" width="19" height="12" rx="2" />
    <path d="M6 10h.01M9 10h.01M12 10h.01M15 10h.01M18 10h.01M6 13.5h.01M9 13.5h.01M15 13.5h.01M18 13.5h.01M8.5 16h7" />
  </Icon>
);
const IconPower   = (p) => <Icon {...p}><path d="M12 4v8" /><path d="M6.3 7.7a8 8 0 1 0 11.4 0" /></Icon>;
const IconWifi    = (p) => (
  <Icon {...p}>
    <path d="M2.5 9.2a14 14 0 0 1 19 0" />
    <path d="M5.5 12.4a10 10 0 0 1 13 0" />
    <path d="M8.5 15.6a6 6 0 0 1 7 0" />
    <circle cx="12" cy="19" r="0.8" fill="currentColor" stroke="none"/>
  </Icon>
);
const IconRefresh = (p) => (
  <Icon {...p}>
    <path d="M3.5 12a8.5 8.5 0 0 1 14.5-6L21 9" />
    <path d="M21 4v5h-5" />
    <path d="M20.5 12a8.5 8.5 0 0 1-14.5 6L3 15" />
    <path d="M3 20v-5h5" />
  </Icon>
);
const IconCheck   = (p) => <Icon {...p}><path d="M5 12.5l4.5 4.5L19 7" /></Icon>;
const IconSettings= (p) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="2.8" />
    <path d="M19.4 13.5a7.7 7.7 0 0 0 0-3l2-1.5-2-3.4-2.4.9a7.7 7.7 0 0 0-2.6-1.5L14 2h-4l-.4 2.5a7.7 7.7 0 0 0-2.6 1.5l-2.4-.9-2 3.4 2 1.5a7.7 7.7 0 0 0 0 3l-2 1.5 2 3.4 2.4-.9a7.7 7.7 0 0 0 2.6 1.5L10 22h4l.4-2.5a7.7 7.7 0 0 0 2.6-1.5l2.4.9 2-3.4z" />
  </Icon>
);
const IconDot     = ({ color = '#3ecf8e', size = 8 }) => (
  <span style={{
    width: size, height: size, borderRadius: '50%', background: color,
    display: 'inline-block', boxShadow: `0 0 0 4px ${color}22`,
  }}/>
);
const IconSpinner = ({ size = 18, color = 'currentColor' }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <circle cx="12" cy="12" r="9" stroke={color} strokeWidth="2.5" opacity="0.2"/>
    <path d="M21 12a9 9 0 0 0-9-9" stroke={color} strokeWidth="2.5" strokeLinecap="round">
      <animateTransform attributeName="transform" type="rotate" from="0 12 12" to="360 12 12" dur="0.9s" repeatCount="indefinite"/>
    </path>
  </svg>
);

Object.assign(window, {
  Icon, IconBack, IconChevron, IconTV, IconPlay, IconPlayPause,
  IconPlus, IconMinus, IconKeyboard, IconPower, IconWifi,
  IconRefresh, IconCheck, IconSettings, IconDot, IconSpinner,
});
