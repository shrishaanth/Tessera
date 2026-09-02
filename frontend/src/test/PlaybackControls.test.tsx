import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PlaybackControls } from "../components/PlaybackControls";

const base = {
  count: 10,
  index: 3,
  playing: false,
  speed: 8,
  atEnd: false,
  timeLabel: "10:15:03",
  onIndex: () => {},
  onTogglePlay: () => {},
  onSpeed: () => {},
};

describe("PlaybackControls", () => {
  it("shows position, time and a scrub slider", () => {
    render(<PlaybackControls {...base} />);
    expect(screen.getByText("4 / 10")).toBeInTheDocument();
    expect(screen.getByText("10:15:03")).toBeInTheDocument();
    const slider = screen.getByRole("slider");
    expect(slider).toHaveValue("3");
    expect(slider).toHaveAttribute("max", "9");
  });

  it("scrubs via the slider", () => {
    const onIndex = vi.fn();
    render(<PlaybackControls {...base} onIndex={onIndex} />);
    fireEvent.change(screen.getByRole("slider"), { target: { value: "7" } });
    expect(onIndex).toHaveBeenCalledWith(7);
  });

  it("toggles play and changes speed", async () => {
    const onTogglePlay = vi.fn();
    const onSpeed = vi.fn();
    render(<PlaybackControls {...base} onTogglePlay={onTogglePlay} onSpeed={onSpeed} />);
    await userEvent.click(screen.getByRole("button", { name: "Play" }));
    expect(onTogglePlay).toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "32×" }));
    expect(onSpeed).toHaveBeenCalledWith(32);
  });

  it("offers Replay when at the end", () => {
    render(<PlaybackControls {...base} atEnd index={9} />);
    expect(screen.getByRole("button", { name: /replay/i })).toBeInTheDocument();
  });
});
