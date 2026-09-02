interface Props {
  count: number;
  index: number;
  playing: boolean;
  speed: number;
  atEnd: boolean;
  timeLabel: string;
  speedOptions?: number[];
  onIndex: (i: number) => void;
  onTogglePlay: () => void;
  onSpeed: (s: number) => void;
}

/** Play/pause + scrub + speed for trajectory replay (FR-5.2). */
export function PlaybackControls({
  count,
  index,
  playing,
  speed,
  atEnd,
  timeLabel,
  speedOptions = [1, 8, 32],
  onIndex,
  onTogglePlay,
  onSpeed,
}: Props) {
  return (
    <div className="playback">
      <button
        className="btn"
        onClick={onTogglePlay}
        disabled={count === 0}
        aria-label={playing ? "Pause" : atEnd ? "Replay" : "Play"}
      >
        {playing ? "❚❚ Pause" : atEnd ? "↻ Replay" : "▶ Play"}
      </button>
      <input
        type="range"
        min={0}
        max={Math.max(0, count - 1)}
        value={index}
        onChange={(e) => onIndex(Number(e.target.value))}
        aria-label="Scrub timeline"
        disabled={count === 0}
      />
      <span className="playback-time mono">{timeLabel}</span>
      <span className="playback-pos mono">
        {count === 0 ? "0 / 0" : `${index + 1} / ${count}`}
      </span>
      <span className="seg">
        {speedOptions.map((s) => (
          <button key={s} className={speed === s ? "on" : ""} onClick={() => onSpeed(s)}>
            {s}×
          </button>
        ))}
      </span>
    </div>
  );
}
