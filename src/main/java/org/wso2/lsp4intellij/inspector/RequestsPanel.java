/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.lsp4intellij.inspector;

import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.Lsp4IntellijBundle;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.logging.LspLogSource;
import org.wso2.lsp4intellij.logging.TracingMessageConsumer.InFlightRequest;
import org.wso2.lsp4intellij.requests.RequestStats;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The Requests tab: a summary line, the requests waiting for a response, and response times per
 * method. All of it comes from {@link LanguageServerWrapper}, which is fed by
 * {@code TracingMessageConsumer} whether or not tracing is on.
 *
 * <p>The pending table is hidden while nothing is waiting, which is most of the time, so the
 * response times get the space. Times of {@link #SLOW_MILLIS} or more are shown in amber, and of
 * {@link #VERY_SLOW_MILLIS} or more in red.
 */
final class RequestsPanel extends JPanel {

    static final long SLOW_MILLIS = 300;
    static final long VERY_SLOW_MILLIS = 1_000;
    private static final JBColor SLOW_COLOR = new JBColor(0xA36B00, 0xE5A50A);
    private static final int MAX_VISIBLE_PENDING_ROWS = 6;
    private static final int METHOD_COLUMN = 0;

    private final JBLabel summary = new JBLabel();
    private final TitledSeparator pendingTitle = new TitledSeparator();
    private final transient RowTable pending = new RowTable(
            new String[]{
                    Lsp4IntellijBundle.message("inspector.requests.column.method"),
                    Lsp4IntellijBundle.message("inspector.requests.column.id"),
                    Lsp4IntellijBundle.message("inspector.requests.column.direction"),
                    Lsp4IntellijBundle.message("inspector.requests.column.waiting")},
            new Class<?>[]{String.class, String.class, String.class, Long.class},
            Lsp4IntellijBundle.message("inspector.requests.none.pending"));
    private final JBScrollPane pendingScroll = new JBScrollPane(pending.table);
    private final transient RowTable responseTimes = new RowTable(
            new String[]{
                    Lsp4IntellijBundle.message("inspector.requests.column.method"),
                    Lsp4IntellijBundle.message("inspector.requests.column.count"),
                    Lsp4IntellijBundle.message("inspector.requests.column.errors"),
                    Lsp4IntellijBundle.message("inspector.requests.column.median"),
                    Lsp4IntellijBundle.message("inspector.requests.column.p95"),
                    Lsp4IntellijBundle.message("inspector.requests.column.max")},
            new Class<?>[]{String.class, Integer.class, Integer.class, Long.class, Long.class, Long.class},
            Lsp4IntellijBundle.message("inspector.requests.none.responses"));

    RequestsPanel() {
        super(new BorderLayout());
        summary.setBorder(JBUI.Borders.empty(6, 8, 2, 8));

        JPanel pendingSection = new JPanel(new BorderLayout());
        pendingSection.add(pendingTitle, BorderLayout.NORTH);
        pendingSection.add(pendingScroll, BorderLayout.CENTER);
        pendingScroll.setVisible(false);

        JPanel top = new JPanel(new BorderLayout());
        top.add(summary, BorderLayout.NORTH);
        top.add(pendingSection, BorderLayout.CENTER);

        JPanel responseSection = new JPanel(new BorderLayout());
        responseSection.add(new TitledSeparator(Lsp4IntellijBundle.message("inspector.requests.response.times")),
                BorderLayout.NORTH);
        responseSection.add(new JBScrollPane(responseTimes.table), BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(responseSection, BorderLayout.CENTER);

        responseTimes.table.getColumnModel().getColumn(2).setCellRenderer(new ErrorCountRenderer());
    }

    void refresh(@Nullable LanguageServerWrapper wrapper) {
        refreshPending(wrapper == null ? List.of() : wrapper.getPendingRequests());
        refreshResponseTimes(wrapper == null ? List.of() : wrapper.getRequestStats().summaries());
    }

    private void refreshPending(@NotNull List<InFlightRequest> requests) {
        pendingTitle.setText(requests.isEmpty()
                ? Lsp4IntellijBundle.message("inspector.requests.pending.none")
                : Lsp4IntellijBundle.message("inspector.requests.pending", requests.size()));
        long now = System.nanoTime();
        List<Object> keys = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (InFlightRequest request : requests) {
            keys.add(request.direction().name() + request.id());
            rows.add(new Object[]{request.method(), request.id(), directionText(request.direction()),
                    (now - request.startNanos()) / 1_000_000});
        }
        boolean resized = pending.update(keys, rows);
        if (resized || pendingScroll.isVisible() == requests.isEmpty()) {
            pendingScroll.setVisible(!requests.isEmpty());
            int visibleRows = Math.min(requests.size(), MAX_VISIBLE_PENDING_ROWS);
            int height = pending.table.getTableHeader().getPreferredSize().height
                    + visibleRows * pending.table.getRowHeight() + JBUI.scale(2);
            pendingScroll.setPreferredSize(new Dimension(0, height));
            revalidate();
        }
    }

    private void refreshResponseTimes(@NotNull List<RequestStats.Summary> summaries) {
        List<Object> keys = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        int responses = 0;
        int errors = 0;
        for (RequestStats.Summary methodSummary : summaries) {
            keys.add(methodSummary.method());
            rows.add(new Object[]{methodSummary.method(), methodSummary.count(), methodSummary.errors(),
                    methodSummary.medianMillis(), methodSummary.p95Millis(), methodSummary.maxMillis()});
            responses += methodSummary.count();
            errors += methodSummary.errors();
        }
        responseTimes.update(keys, rows);
        summary.setText(summaryText(summaries, responses, errors));
    }

    @NotNull
    private static String summaryText(@NotNull List<RequestStats.Summary> summaries, int responses, int errors) {
        if (summaries.isEmpty()) {
            return Lsp4IntellijBundle.message("inspector.requests.none.responses");
        }
        RequestStats.Summary slowest = summaries.stream()
                .max(Comparator.comparingLong(RequestStats.Summary::p95Millis))
                .orElseThrow();
        return Lsp4IntellijBundle.message("inspector.requests.summary", responses, errors,
                shortMethod(slowest.method()), duration(slowest.p95Millis()));
    }

    @NotNull
    static String duration(long millis) {
        return millis < 1_000 ? millis + " ms" : String.format(Locale.ROOT, "%.1f s", millis / 1_000.0);
    }

    /**
     * Returns the part of a method after its last {@code /}, such as {@code completion} for
     * {@code textDocument/completion}.
     */
    @NotNull
    static String shortMethod(@NotNull String method) {
        return method.substring(method.lastIndexOf('/') + 1);
    }

    @Nullable
    static Color durationColor(long millis) {
        if (millis >= VERY_SLOW_MILLIS) {
            return LspInspectorPanel.ERROR_COLOR;
        }
        return millis >= SLOW_MILLIS ? SLOW_COLOR : null;
    }

    @NotNull
    private static String directionText(@NotNull LspLogSource direction) {
        return Lsp4IntellijBundle.message(direction == LspLogSource.TRACE_OUT
                ? "inspector.requests.direction.out"
                : "inspector.requests.direction.in");
    }

    /**
     * Shows a method's namespace dimmed and its name in bold, so the names line up visually.
     */
    private static final class MethodRenderer extends ColoredTableCellRenderer {

        @Override
        protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value, boolean selected,
                boolean hasFocus, int row, int column) {
            String method = String.valueOf(value);
            int slash = method.lastIndexOf('/');
            if (slash >= 0) {
                append(method.substring(0, slash + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            append(method.substring(slash + 1), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        }
    }

    private static class CenteredRenderer extends DefaultTableCellRenderer {

        CenteredRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        /**
         * Applies {@code color}, or the table's normal foreground when it is null or the row is selected.
         */
        Component colored(@NotNull Component component, @NotNull JTable table, boolean selected,
                @Nullable Color color) {
            if (!selected) {
                component.setForeground(color == null ? table.getForeground() : color);
            }
            return component;
        }
    }

    private static final class DurationRenderer extends CenteredRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean hasFocus, int row, int column) {
            long millis = (Long) value;
            Component component = super.getTableCellRendererComponent(table, duration(millis), selected, hasFocus,
                    row, column);
            return colored(component, table, selected, durationColor(millis));
        }
    }

    private static final class ErrorCountRenderer extends CenteredRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, selected, hasFocus, row,
                    column);
            return colored(component, table, selected,
                    (Integer) value > 0 ? LspInspectorPanel.ERROR_COLOR : null);
        }
    }

    /**
     * A read-only, sortable table whose rows are replaced on every refresh. Rows are matched by key, so
     * while the same rows are showing only the changed ones are repainted and the selection is kept.
     */
    private static final class RowTable {

        private final JBTable table;
        private List<Object> keys = List.of();
        private List<Object[]> rows = new ArrayList<>();

        RowTable(@NotNull String[] columns, @NotNull Class<?>[] columnClasses, @NotNull String emptyText) {
            table = new JBTable(new AbstractTableModel() {
                @Override
                public int getRowCount() {
                    return rows.size();
                }

                @Override
                public int getColumnCount() {
                    return columns.length;
                }

                @Override
                public String getColumnName(int column) {
                    return columns[column];
                }

                @Override
                public Class<?> getColumnClass(int column) {
                    return columnClasses[column];
                }

                @Override
                public Object getValueAt(int row, int column) {
                    return rows.get(row)[column];
                }
            });
            table.setStriped(true);
            table.setShowGrid(false);
            table.setAutoCreateRowSorter(true);
            table.getEmptyText().setText(emptyText);
            table.getColumnModel().getColumn(METHOD_COLUMN).setPreferredWidth(260);
            table.getColumnModel().getColumn(METHOD_COLUMN).setCellRenderer(new MethodRenderer());
            for (int column = 1; column < columns.length; column++) {
                table.getColumnModel().getColumn(column).setCellRenderer(
                        columnClasses[column] == Long.class ? new DurationRenderer() : new CenteredRenderer());
            }
            LspInspectorPanel.centerHeadersFromColumn(table, 1);
        }

        /**
         * Replaces the rows, returning whether the set of rows changed.
         */
        boolean update(@NotNull List<Object> newKeys, @NotNull List<Object[]> newRows) {
            AbstractTableModel model = (AbstractTableModel) table.getModel();
            if (!newKeys.equals(keys)) {
                keys = newKeys;
                rows = newRows;
                model.fireTableDataChanged();
                return true;
            }
            for (int row = 0; row < newRows.size(); row++) {
                if (!Arrays.equals(rows.get(row), newRows.get(row))) {
                    rows.set(row, newRows.get(row));
                    model.fireTableRowsUpdated(row, row);
                }
            }
            return false;
        }
    }
}
