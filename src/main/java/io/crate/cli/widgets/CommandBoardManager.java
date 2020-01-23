/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.cli.widgets;

import io.crate.cli.backend.SQLConnection;
import io.crate.cli.backend.SQLExecutionRequest;
import io.crate.cli.common.*;
import io.crate.cli.store.JsonStore;
import io.crate.cli.store.Store;
import io.crate.cli.store.StoreItem;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.Closeable;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;


public class CommandBoardManager extends JPanel implements EventSpeaker<CommandBoardManager.EventType>, Closeable {

    public enum EventType {
        COMMAND_AVAILABLE,
        COMMAND_CANCEL,
        BOARD_CHANGE
    }

    private static final String BORDER_TITLE = "Connection";
    public static final int NUM_COMMAND_BOARDS = 8; // A,B,C,D,E,F,G,H

    public static int fromCommandBoardKey(String key) {
        if (null == key || key.trim().length() < 1) {
            throw new IllegalArgumentException(String.format(
                    Locale.ENGLISH,
                    "key cannot be null, must contain one non white char: %s",
                    key));
        }
        int offset = key.trim().charAt(0) - 'A';
        if (offset < 0 || offset >= NUM_COMMAND_BOARDS) {
            throw new IndexOutOfBoundsException(String.format(
                    Locale.ENGLISH,
                    "Key [%s] -> offset: %d (max: %d)",
                    key, offset, NUM_COMMAND_BOARDS - 1));
        }
        return offset;
    }

    public static String toCommandBoardKey(int offset) {
        if (offset < 0 || offset >= NUM_COMMAND_BOARDS) {
            throw new IllegalArgumentException(String.format(
                    Locale.ENGLISH,
                    "offset: %d out of range (max: %d)",
                    offset,
                    NUM_COMMAND_BOARDS - 1));
        }
        return String.valueOf((char) ('A' + offset));
    }


    private static class CommandBoardManagerData implements Closeable {


        private enum AttributeName implements HasKey {
            board_contents;

            @Override
            public String getKey() {
                return name();
            }
        }

        public static class BoardItem extends StoreItem {

            private transient SQLConnection sqlConnection;

            public BoardItem(StoreItem other) {
                super(other);
            }

            public BoardItem(String name) {
                super(name);
            }

            public void setSqlConnection(SQLConnection conn) {
                sqlConnection = conn;
            }

            public SQLConnection getSqlConnection() {
                return sqlConnection;
            }

            @Override
            public final String getKey() {
                return String.format(Locale.ENGLISH, "%s", name);
            }
        }


        private final Store<CommandBoardManagerData.BoardItem> store;
        private CommandBoardManagerData.BoardItem[] boards;
        private int currentIdx;


        CommandBoardManagerData() {
            int size = CommandBoardManager.NUM_COMMAND_BOARDS;
            store = new JsonStore<>(
                    GUIToolkit.COMMAND_BOARD_MANAGER_STORE,
                    CommandBoardManagerData.BoardItem.class) {

                @Override
                public BoardItem[] defaultStoreEntries() {
                    return new BoardItem[0];
                }
            };
            currentIdx = 0;
            store.load();
            boards = new BoardItem[Math.max(size, store.size() % size)];
            Arrays.fill(boards, null);
            store.values().toArray(boards);
            arrangeDescriptorsByKey();
        }

        private void arrangeDescriptorsByKey() {
            for (int i = 0; i < boards.length; i++) {
                CommandBoardManagerData.BoardItem bi = boards[i];
                if (null != bi) {
                    int idx = CommandBoardManager.fromCommandBoardKey(bi.getKey());
                    if (idx != i) {
                        CommandBoardManagerData.BoardItem tmp = boards[i];
                        boards[i] = boards[idx];
                        boards[idx] = tmp;
                    }
                }
            }
        }

        void store() {
            store.addAll(true, boards);
        }

        String getCurrentKey() {
            return CommandBoardManager.toCommandBoardKey(currentIdx);
        }

        int getCurrentIdx() {
            return currentIdx;
        }

        void setCurrentIdx(int idx) {
            currentIdx = idx;
        }

        private CommandBoardManagerData.BoardItem current() {
            return current(currentIdx);
        }

        private CommandBoardManagerData.BoardItem current(int idx) {
            if (null == boards[idx]) {
                boards[idx] = new CommandBoardManagerData.BoardItem(CommandBoardManager.toCommandBoardKey(idx));
            }
            return boards[idx];
        }

        SQLConnection getCurrentSQLConnection() {
            SQLConnection conn = current().getSqlConnection();
            if (null == conn) {
                conn = findFirstConnection(true);
                if (null == conn) {
                    conn = findFirstConnection(false);
                }
                current().setSqlConnection(conn);
            }
            return conn;
        }

        void setCurrentSQLConnection(SQLConnection conn) {
            current().setSqlConnection(conn);
        }

        String getCurrentBoardContent() {
            return current().getAttribute(CommandBoardManagerData.AttributeName.board_contents);
        }

        void setCurrentBoardContents(String text) {
            current().setAttribute(CommandBoardManagerData.AttributeName.board_contents, text);
        }

        @Override
        public void close() {
            store.close();
        }

        private SQLConnection findFirstConnection(boolean checkIsConnected) {
            for (int i = 0; i < boards.length; i++) {
                SQLConnection conn = current(i).getSqlConnection();
                if (null != conn) {
                    if (checkIsConnected) {
                        if (conn.isConnected()) {
                            return conn;
                        }
                    } else {
                        return conn;
                    }
                }
            }
            return null;
        }
    }


    private final JTextPane textPane;
    private final JButton clearButton;
    private final JButton runButton;
    private final JButton runLineButton;
    private final JButton cancelButton;
    private final JButton[] boardHeaderButtons;
    private final TitledBorder titleBorder;
    private final CommandBoardManagerData commandBoardManagerData;
    private final EventListener<CommandBoardManager, SQLExecutionRequest> eventListener;


    public CommandBoardManager(EventListener<CommandBoardManager, SQLExecutionRequest> eventListener) {
        this.eventListener = eventListener;
        commandBoardManagerData = new CommandBoardManagerData();
        textPane = new JTextPane();
        textPane.setCaretPosition(0);
        textPane.setMargin(new Insets(5, 5, 5, 5));
        textPane.setFont(GUIToolkit.COMMAND_BOARD_BODY_FONT);
        textPane.setForeground(GUIToolkit.COMMAND_BOARD_FONT_COLOR);
        textPane.setBackground(GUIToolkit.COMMAND_BOARD_BACKGROUND_COLOR);
        textPane.setCaretColor(GUIToolkit.COMMAND_BOARD_CARET_COLOR);
        AbstractDocument abstractDocument = (AbstractDocument) textPane.getDocument();
        abstractDocument.setDocumentFilter(new KeywordDocumentFilter(textPane.getStyledDocument()));
        String boardContent = commandBoardManagerData.getCurrentBoardContent();
        textPane.setText(boardContent);
        JPanel bufferButtonsPanel = new JPanel(new GridLayout(1, NUM_COMMAND_BOARDS, 0, 5));
        boardHeaderButtons = new JButton[NUM_COMMAND_BOARDS];
        for (int i = 0; i < NUM_COMMAND_BOARDS; i++) {
            int boardIdx = i;
            JButton button = new JButton(toCommandBoardKey(boardIdx));
            button.addActionListener(e -> onBoardBufferEvent(boardIdx));
            button.setFont(GUIToolkit.COMMAND_BOARD_HEADER_FONT);
            boardHeaderButtons[boardIdx] = button;
            bufferButtonsPanel.add(button);
        }
        clearButton = new JButton("Clear");
        clearButton.addActionListener(this::onClearButtonEvent);
        runLineButton = new JButton("L.Run");
        runLineButton.addActionListener(this::onRunCurrentLineEvent);
        runLineButton.setEnabled(false);
        runButton = new JButton("Run");
        runButton.addActionListener(this::onRunEvent);
        runButton.setEnabled(false);
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(this::onCancelButtonEvent);
        cancelButton.setEnabled(false);
        JPanel actionButtonsPanel = new JPanel(new GridLayout(1, 3, 0, 0));
        actionButtonsPanel.setBorder(BorderFactory.createEtchedBorder());
        actionButtonsPanel.add(clearButton);
        actionButtonsPanel.add(runLineButton);
        actionButtonsPanel.add(runButton);
        actionButtonsPanel.add(cancelButton);
        JPanel bufferActionButtonsPanel = new JPanel(new BorderLayout());
        bufferActionButtonsPanel.add(bufferButtonsPanel, BorderLayout.CENTER);
        bufferActionButtonsPanel.add(actionButtonsPanel, BorderLayout.EAST);
        titleBorder = BorderFactory.createTitledBorder(BORDER_TITLE);
        titleBorder.setTitleFont(GUIToolkit.COMMAND_BOARD_HEADER_FONT);
        titleBorder.setTitleColor(GUIToolkit.TABLE_HEADER_FONT_COLOR);
        setBorder(titleBorder);
        JScrollPane scrollPane = new JScrollPane(
                textPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        setLayout(new BorderLayout());
        add(bufferActionButtonsPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        setPreferredSize(GUIToolkit.COMMAND_BOARD_MANAGER_HEIGHT);
        toggleComponents();
    }

    public SQLConnection getSQLConnection() {
        return commandBoardManagerData.getCurrentSQLConnection();
    }

    public void setSQLConnection(SQLConnection conn) {
        commandBoardManagerData.setCurrentSQLConnection(conn);
        toggleComponents();
    }

    private String getFullTextContents() {
        try {
            int len = textPane.getStyledDocument().getLength();
            return textPane.getStyledDocument().getText(0, len);
        } catch (BadLocationException e) {
            throw new RuntimeException(e);
        }
    }

    private String getCommand() {
        String command = textPane.getSelectedText();
        if (null == command) {
            command = getFullTextContents();
        }
        return command.trim();
    }

    private String getCurrentLine() {
        try {
            int caretPos = textPane.getCaretPosition();
            int start = Utilities.getRowStart(textPane, caretPos);
            int end = Utilities.getRowEnd(textPane, caretPos) - 1;
            String command = textPane.getStyledDocument().getText(start, end - start + 1);
            return command.trim();
        } catch (BadLocationException e) {
            throw new RuntimeException(e);
        }
    }

    private void toggleComponents() {
        for (int i = 0; i < boardHeaderButtons.length; i++) {
            boardHeaderButtons[i].setBorder(GUIToolkit.COMMAND_BOARD_UNSELECTED_BORDER);
        }
        SQLConnection conn = commandBoardManagerData.getCurrentSQLConnection();
        if (null != conn) {
            titleBorder.setTitle(String.format(
                    Locale.ENGLISH,
                    "%s [%s]",
                    BORDER_TITLE,
                    conn.getKey()));
            validate();
            repaint();
        }
        boolean isConnected = null != conn && conn.isConnected();
        Border border = isConnected ?
                GUIToolkit.COMMAND_BOARD_CONNECTED_BORDER :
                GUIToolkit.COMMAND_BOARD_DISCONNECTED_BORDER;
        boardHeaderButtons[commandBoardManagerData.getCurrentIdx()].setBorder(border);
        runButton.setEnabled(isConnected);
        runLineButton.setEnabled(isConnected);
        cancelButton.setEnabled(isConnected);
    }

    public void store() {
        commandBoardManagerData.setCurrentBoardContents(getFullTextContents());
        commandBoardManagerData.store();
    }

    @Override
    public void requestFocus() {
        super.requestFocus();
        textPane.requestFocus();
    }

    @Override
    public void close() {
        commandBoardManagerData.setCurrentBoardContents(getFullTextContents());
        commandBoardManagerData.close();
    }

    public void onBoardBufferEvent(int newIdx) {
        commandBoardManagerData.setCurrentBoardContents(getFullTextContents());
        commandBoardManagerData.setCurrentIdx(newIdx);
        textPane.setText(commandBoardManagerData.getCurrentBoardContent());
        toggleComponents();
        eventListener.onSourceEvent(
                this,
                EventType.BOARD_CHANGE,
                new SQLExecutionRequest(
                        commandBoardManagerData.getCurrentKey(),
                        commandBoardManagerData.getCurrentSQLConnection(),
                        commandBoardManagerData.getCurrentBoardContent()));
    }

    public void onClearButtonEvent(ActionEvent event) {
        commandBoardManagerData.setCurrentBoardContents("");
        textPane.setText("");
    }

    public void onCancelButtonEvent(ActionEvent event) {
        SQLConnection conn = commandBoardManagerData.getCurrentSQLConnection();
        if (null == conn || false == conn.isConnected()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Not connected");
            return;
        }
        eventListener.onSourceEvent(
                this,
                EventType.COMMAND_CANCEL,
                new SQLExecutionRequest(commandBoardManagerData.getCurrentKey(), conn, "CANCEL"));
    }

    public void onRunCurrentLineEvent(ActionEvent event) {
        fireCommandEvent(this::getCurrentLine);
    }

    public void onRunEvent(ActionEvent event) {
        fireCommandEvent(this::getCommand);
    }

    private void fireCommandEvent(Supplier<String> commandSupplier) {
        SQLConnection conn = commandBoardManagerData.getCurrentSQLConnection();
        if (null == conn) {
            JOptionPane.showMessageDialog(
                    this,
                    "Connection not selected");
            return;
        }
        String command = commandSupplier.get();
        if (null == command || command.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Command is empty");
            return;
        }
        eventListener.onSourceEvent(
                this,
                EventType.COMMAND_AVAILABLE,
                new SQLExecutionRequest(commandBoardManagerData.getCurrentKey(), conn, command));
    }
}
