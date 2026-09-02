import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { SiteView } from "../api/types";
import { SitePanel } from "../components/SitePanel";

const site: SiteView = {
  id: "SITE-abc",
  name: "Acme Corp",
  address: "1 Industrial Way",
  kind: "RADIUS",
  outline: [],
  centerLat: 42.356,
  centerLon: -71.0635,
  radiusMeters: 150,
  dwellAlertSeconds: 1800,
  createdAtEpochMs: Date.now(),
};

function noop() {}
const asyncNoop = () => Promise.resolve();

describe("SitePanel", () => {
  it("lists sites and deletes on click", async () => {
    const onDelete = vi.fn().mockResolvedValue(undefined);
    render(
      <SitePanel
        sites={[site]}
        mode="idle"
        drawPoints={[]}
        radiusCenter={null}
        onStartPolygon={noop}
        onStartRadius={noop}
        onCancel={noop}
        onFinishPolygon={asyncNoop}
        onFinishRadius={asyncNoop}
        onDelete={onDelete}
      />,
    );
    expect(screen.getByText("Acme Corp")).toBeInTheDocument();
    expect(screen.getByText(/radius 150 m · alert 30 min/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onDelete).toHaveBeenCalledWith("SITE-abc");
  });

  it("starts a polygon draw", async () => {
    const onStartPolygon = vi.fn();
    render(
      <SitePanel
        sites={[]}
        mode="idle"
        drawPoints={[]}
        radiusCenter={null}
        onStartPolygon={onStartPolygon}
        onStartRadius={noop}
        onCancel={noop}
        onFinishPolygon={asyncNoop}
        onFinishRadius={asyncNoop}
        onDelete={asyncNoop}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /draw polygon/i }));
    expect(onStartPolygon).toHaveBeenCalled();
  });

  it("keeps Create disabled until a polygon has 3+ points and a name", async () => {
    const onFinishPolygon = vi.fn().mockResolvedValue(undefined);
    const { rerender } = render(
      <SitePanel
        sites={[]}
        mode="sitePoly"
        drawPoints={[[1, 1], [2, 2]]}
        radiusCenter={null}
        onStartPolygon={noop}
        onStartRadius={noop}
        onCancel={noop}
        onFinishPolygon={onFinishPolygon}
        onFinishRadius={asyncNoop}
        onDelete={asyncNoop}
      />,
    );
    const create = () => screen.getByRole("button", { name: /create site/i });
    expect(create()).toBeDisabled();

    await userEvent.type(screen.getByPlaceholderText("Site name"), "New Depot");
    expect(create()).toBeDisabled(); // still only 2 points

    rerender(
      <SitePanel
        sites={[]}
        mode="sitePoly"
        drawPoints={[[1, 1], [2, 2], [3, 3]]}
        radiusCenter={null}
        onStartPolygon={noop}
        onStartRadius={noop}
        onCancel={noop}
        onFinishPolygon={onFinishPolygon}
        onFinishRadius={asyncNoop}
        onDelete={asyncNoop}
      />,
    );
    expect(create()).toBeEnabled();
    await userEvent.click(create());
    expect(onFinishPolygon).toHaveBeenCalledWith("New Depot", null);
  });
});
