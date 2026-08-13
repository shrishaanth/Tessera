package com.geotracker.dashboard;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;
import com.geotracker.model.Position;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class SwingDashboard extends JFrame {
    private final CowQuadtree quadtree;
    private final HamtIndex hamt;
    private final BoundingBox mapBounds;
    private final JPanel canvasPanel;
    private final JLabel statsLabel;
    private volatile long frameCount = 0;
    private volatile long lastFpsTime = System.currentTimeMillis();
    private volatile int fps = 0;
    private volatile long totalUpdates = 0;
    private volatile double viewportScale = 1.0;
    private volatile double viewportCenterX = 500.0;
    private volatile double viewportCenterY = 500.0;
    private volatile boolean running = true;

    public SwingDashboard(CowQuadtree quadtree, HamtIndex hamt, BoundingBox mapBounds) {
        this.quadtree = quadtree;
        this.hamt = hamt;
        this.mapBounds = mapBounds;
        this.canvasPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                render((Graphics2D) g);
            }
        };
        this.statsLabel = new JLabel("Initializing...");
        initUI();
        startRenderLoop();
    }

    private void initUI() {
        setTitle("Tessera Dashboard");
        setSize(1000, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        canvasPanel.setBackground(new Color(30, 30, 30));
        canvasPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(e.getX(), e.getY());
            }
        });

        JPanel controls = new JPanel();
        JButton zoomIn = new JButton("Zoom In");
        JButton zoomOut = new JButton("Zoom Out");
        JButton resetView = new JButton("Reset View");
        zoomIn.addActionListener(e -> viewportScale *= 0.8);
        zoomOut.addActionListener(e -> viewportScale *= 1.25);
        resetView.addActionListener(e -> {
            viewportScale = 1.0;
            viewportCenterX = (mapBounds.minX() + mapBounds.maxX()) / 2.0;
            viewportCenterY = (mapBounds.minY() + mapBounds.maxY()) / 2.0;
        });
        controls.add(zoomIn);
        controls.add(zoomOut);
        controls.add(resetView);

        add(canvasPanel, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
        add(statsLabel, BorderLayout.NORTH);
    }

    private void startRenderLoop() {
        Timer timer = new Timer(16, e -> {
            canvasPanel.repaint();
            updateStats();
        });
        timer.start();
    }

    private void render(Graphics2D g) {
        int width = canvasPanel.getWidth();
        int height = canvasPanel.getHeight();

        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, width, height);

        double halfWidth = (width / 2.0) / viewportScale;
        double halfHeight = (height / 2.0) / viewportScale;
        double minX = viewportCenterX - halfWidth;
        double maxX = viewportCenterX + halfWidth;
        double minY = viewportCenterY - halfHeight;
        double maxY = viewportCenterY + halfHeight;

        BoundingBox viewport = new BoundingBox(minX, minY, maxX, maxY);
        List<Long> vehicleIds = quadtree.rangeQuery(viewport);

        g.setColor(new Color(60, 60, 60));
        g.drawRect(0, 0, width - 1, height - 1);

        for (long vehicleId : vehicleIds) {
            Position pos = hamt.get(vehicleId);
            if (pos == null) continue;

            int screenX = (int) ((pos.x() - minX) * viewportScale);
            int screenY = (int) ((pos.y() - minY) * viewportScale);

            if (screenX >= 0 && screenX < width && screenY >= 0 && screenY < height) {
                g.setColor(new Color(0, 150, 255));
                g.fillOval(screenX - 2, screenY - 2, 4, 4);
            }
        }

        frameCount++;
    }

    private void updateStats() {
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            fps = (int) (frameCount * 1000 / (now - lastFpsTime));
            frameCount = 0;
            lastFpsTime = now;
        }
        statsLabel.setText(String.format("FPS: %d | Vehicles: %d | Scale: %.2f | Center: (%.0f, %.0f)",
                fps, quadtree.rangeQuery(mapBounds).size(), viewportScale, viewportCenterX, viewportCenterY));
    }

    private void handleClick(int screenX, int screenY) {
        int width = canvasPanel.getWidth();
        int height = canvasPanel.getHeight();
        double halfWidth = (width / 2.0) / viewportScale;
        double halfHeight = (height / 2.0) / viewportScale;
        double minX = viewportCenterX - halfWidth;
        double minY = viewportCenterY - halfHeight;

        double clickX = minX + screenX / viewportScale;
        double clickY = minY + screenY / viewportScale;

        NearestResult nearest = quadtree.nearest(clickX, clickY);
        if (nearest != null && nearest.distance() < 50.0 / viewportScale) {
            JOptionPane.showMessageDialog(this,
                    "Vehicle " + nearest.vehicleId() + "\nPosition: (" + nearest.x() + ", " + nearest.y() + ")\nDistance: " + String.format("%.2f", nearest.distance()),
                    "Vehicle Info", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public void setTotalUpdates(long totalUpdates) {
        this.totalUpdates = totalUpdates;
    }

    public void stop() {
        running = false;
    }
}
