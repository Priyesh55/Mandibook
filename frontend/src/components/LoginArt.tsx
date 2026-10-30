/** Decorative line art for the sign-in page (no meaning of its own, hidden from assistive technology). Colour comes from `currentColor`. */

const LINE = { fill: "none", stroke: "currentColor", strokeWidth: 3, strokeLinecap: "round", strokeLinejoin: "round" } as const;

/** A truck piled with sacks on the road to the mandi. */
export function TruckArt({ className }: { className?: string }) {
  const sack = (x: number, y: number) => (
    <g key={`${x}-${y}`}>
      <rect x={x} y={y} width={48} height={40} rx={16} />
      <path d={`M${x + 18} ${y + 9}h12`} strokeWidth={2} />
    </g>
  );
  return (
    <svg className={className} viewBox="0 0 480 200" preserveAspectRatio="xMinYMax meet" aria-hidden="true" focusable="false" {...LINE}>
      {/* motion */}
      <path d="M8 88h26M0 110h22M12 132h18" strokeWidth={2.5} opacity={0.6} />
      {/* sacks, two rows */}
      {[50, 102, 154, 206, 258].map((x) => sack(x, 60))}
      {[76, 128, 180, 232].map((x) => sack(x, 22))}
      {/* bed and chassis */}
      <rect x={40} y={100} width={290} height={42} rx={6} />
      <path d="M40 142H440" />
      {/* cab */}
      <path d="M330 142V76h52l44 36v30" />
      <path d="M342 88h36l30 24h-66z" />
      <path d="M426 142h14M436 124v18" />
      {/* wheels */}
      {[110, 170, 385].map((x) => (
        <g key={x}>
          <circle cx={x} cy={160} r={18} />
          <circle cx={x} cy={160} r={5} />
        </g>
      ))}
      {/* road */}
      <path d="M0 178h480" />
      <path d="M20 192h40M100 192h40M180 192h40M260 192h40M340 192h40M420 192h40" strokeWidth={2.5} opacity={0.6} />
    </svg>
  );
}

/** Crates, a sack and a beam balance: what a mandi weighs and counts. */
export function MandiArt({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 390 150" preserveAspectRatio="xMidYMax meet" aria-hidden="true" focusable="false" {...LINE}>
      {/* crates */}
      <g>
        <rect x={20} y={90} width={80} height={50} rx={3} />
        <path d="M20 107h80M20 124h80" />
        <rect x={104} y={90} width={80} height={50} rx={3} />
        <path d="M104 107h80M104 124h80" />
        <rect x={62} y={40} width={80} height={50} rx={3} />
        <path d="M62 57h80M62 74h80" />
      </g>
      {/* sack */}
      <path d="M215 140c-10-30 0-55 17-62l-4-12h28l-4 12c18 7 28 32 18 62z" />
      <path d="M232 66h20" strokeWidth={2} />
      {/* beam balance */}
      <path d="M300 140h40M320 140V50" />
      <circle cx={320} cy={46} r={5} />
      <path d="M284 60h72" />
      <path d="M284 60l-14 36h28zM356 60l-14 36h28z" />
      {/* ground */}
      <path d="M0 144h390" strokeWidth={2.5} opacity={0.6} />
    </svg>
  );
}
