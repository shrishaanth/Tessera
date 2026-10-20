package com.geotracker.fx.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * A card with a live line chart over the last N seconds: x is "seconds ago" (fixed window), y has one
 * axis with a unit. Shows the latest values in the header and the hovered values under a crosshair.
 */
public final class TimeSeriesChart extends VBox {

    private final int windowSeconds;
    private final String unit;
    private final List<String> names;
    private final NumberAxis xAxis;
    private final NumberAxis yAxis;
    private final CrosshairChart chart;
    private final List<XYChart.Series<Number, Number>> series = new ArrayList<>();
    private final Label readout = Ui.label("", "chart-readout");
    private final DoubleFunction<String> valueFormat;
    private long[] times = new long[0];
    private double[][] values = new double[0][];
    private long nowMillis;
    private boolean hovering = false;

    public TimeSeriesChart(String title, String subtitle, String yLabel, String unit, List<String> seriesNames,
                           int windowSeconds, DoubleFunction<String> valueFormat) {
        super(6);
        this.windowSeconds = windowSeconds;
        this.unit = unit;
        this.names = seriesNames;
        this.valueFormat = valueFormat;
        getStyleClass().addAll("card", "chart-card");

        xAxis = new NumberAxis(-windowSeconds, 0, windowSeconds >= 120 ? 30 : 15);
        xAxis.setAutoRanging(false);
        xAxis.setMinorTickVisible(false);
        xAxis.setLabel("Seconds ago");
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number n) { return n.intValue() == 0 ? "now" : String.valueOf(-n.intValue()); }
            @Override public Number fromString(String s) { return 0; }
        });
        yAxis = new NumberAxis();
        yAxis.setForceZeroInRange(true);
        yAxis.setMinorTickVisible(false);
        yAxis.setLabel(yLabel);
        yAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number n) { return axisNumber(n.doubleValue()); }
            @Override public Number fromString(String s) { return 0; }
        });

        chart = new CrosshairChart(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(seriesNames.size() > 1);
        chart.setHorizontalZeroLineVisible(false);
        chart.setVerticalZeroLineVisible(false);
        chart.setAlternativeRowFillVisible(false);
        chart.setMinHeight(200);
        chart.setPrefHeight(230);
        for (String name : seriesNames) {
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            s.setName(name);
            series.add(s);
            chart.getData().add(s);
        }
        VBox.setVgrow(chart, Priority.ALWAYS);

        Label sub = Ui.label(subtitle, "card-subtitle");
        sub.setWrapText(true);
        VBox header = new VBox(3, Ui.label(title, "card-title"), sub, readout);
        getChildren().addAll(header, chart);

        chart.setOnMouseMoved(e -> {
            Point2D p = xAxis.sceneToLocal(e.getSceneX(), e.getSceneY());
            double xv = xAxis.getValueForDisplay(p.getX()).doubleValue();
            if (xv < -windowSeconds || xv > 0 || times.length == 0) {
                hovering = false;
                chart.hideCrosshair();
                showLatest();
                return;
            }
            hovering = true;
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < times.length; i++) {
                double d = Math.abs((times[i] - nowMillis) / 1000.0 - xv);
                if (d < bestD) { bestD = d; best = i; }
            }
            double at = (times[best] - nowMillis) / 1000.0;
            chart.showCrosshair(at);
            readout.setText(String.format(Locale.US, "%.0f s ago:  ", -at) + describe(best));
        });
        chart.setOnMouseExited(e -> {
            hovering = false;
            chart.hideCrosshair();
            showLatest();
        });
    }

    /** Replaces the data. {@code samples[i]} holds one value per series for time {@code sampleTimes[i]}. */
    public void update(long[] sampleTimes, double[][] samples, long now) {
        this.times = sampleTimes;
        this.values = samples;
        this.nowMillis = now;
        for (int s = 0; s < series.size(); s++) {
            List<XYChart.Data<Number, Number>> data = new ArrayList<>(sampleTimes.length);
            for (int i = 0; i < sampleTimes.length; i++) {
                double x = (sampleTimes[i] - now) / 1000.0;
                if (x < -windowSeconds - 1) continue;
                data.add(new XYChart.Data<>(x, samples[i][s]));
            }
            series.get(s).getData().setAll(data);
        }
        if (!hovering) showLatest();
    }

    private void showLatest() {
        if (values.length == 0) {
            readout.setText("No data yet");
            return;
        }
        readout.setText("Latest:  " + describe(values.length - 1));
    }

    private String describe(int index) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < names.size(); s++) {
            if (s > 0) sb.append("  ·  ");
            if (names.size() > 1) sb.append(names.get(s)).append(' ');
            sb.append(valueFormat.apply(values[index][s]));
        }
        return sb.append(' ').append(unit).toString();
    }

    private static String axisNumber(double v) {
        double a = Math.abs(v);
        if (a >= 1000) return String.format(Locale.US, "%,.0f", v);
        if (a >= 10 || v == 0) return String.format(Locale.US, "%.0f", v);
        if (a >= 1) return String.format(Locale.US, "%.1f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    /** LineChart with a vertical crosshair line in its plot area. */
    private static final class CrosshairChart extends LineChart<Number, Number> {
        private final Line crosshair = new Line();
        private double crosshairX = Double.NaN;

        CrosshairChart(NumberAxis x, NumberAxis y) {
            super(x, y);
            crosshair.getStyleClass().add("chart-crosshair");
            crosshair.setVisible(false);
            crosshair.setMouseTransparent(true);
            getPlotChildren().add(crosshair);
        }

        void showCrosshair(double x) {
            crosshairX = x;
            crosshair.setVisible(true);
            requestChartLayout();
            layoutPlotChildren();
        }

        void hideCrosshair() {
            crosshairX = Double.NaN;
            crosshair.setVisible(false);
        }

        @Override
        protected void layoutPlotChildren() {
            super.layoutPlotChildren();
            if (!Double.isNaN(crosshairX)) {
                double px = getXAxis().getDisplayPosition(crosshairX);
                crosshair.setStartX(px);
                crosshair.setEndX(px);
                crosshair.setStartY(0);
                crosshair.setEndY(((NumberAxis) getYAxis()).getHeight());
                crosshair.toFront();
            }
        }
    }
}
