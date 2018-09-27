/* -*-mode:java; c-basic-offset:2; -*- */
/*
 * TermPanel
 *
 * Copyright (C) 2002-2004 ymnk, JCraft,Inc.
 * Copyright (C) 2018 Bernd Eilers
 *
 * Written by: 2002 ymnk<ymnk@jcaft.com>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public License
 * as published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Library General Public License for more details.
 * You should have received a copy of the GNU Library General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package net.agilhard.terminal.emulation.swing;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Event;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BoundedRangeModel;
import javax.swing.DefaultBoundedRangeModel;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.agilhard.terminal.emulation.BackBuffer;
import net.agilhard.terminal.emulation.Emulator;
import net.agilhard.terminal.emulation.RequestOrigin;
import net.agilhard.terminal.emulation.ResizePanelDelegate;
import net.agilhard.terminal.emulation.ScrollBuffer;
import net.agilhard.terminal.emulation.SelectionListener;
import net.agilhard.terminal.emulation.SelectionRunConsumer;
import net.agilhard.terminal.emulation.Style;
import net.agilhard.terminal.emulation.StyleState;
import net.agilhard.terminal.emulation.StyledRunConsumer;
import net.agilhard.terminal.emulation.TerminalDisplay;
import net.agilhard.terminal.emulation.Util;

/**
 * The Class TermPanel.
 */
public class TermPanel extends JComponent implements TerminalDisplay, ClipboardOwner, StyledRunConsumer {

    /** The Logger. */
    private final Logger log = LoggerFactory.getLogger(TermPanel.class);

    /** The Constant serialVersionUID. */
    private static final long serialVersionUID = -1048763516632093014L;

    /** The Constant FPS. */
    private static final double FPS = 20;

    /** The img. */
    private BufferedImage img;

    /** The gfx. */
    private Graphics2D gfx;

    /** The term component. */
    private final Component termComponent = this;

    /** The normal font. */
    private final Font normalFont;

    /** The bold font. */
    private final Font boldFont;

    /** The descent. */
    private int descent;

    /** The line space. */
    private int lineSpace;

    /** The char size. */
    private final Dimension charSize = new Dimension();

    /** The term size. */
    private Dimension termSize = new Dimension(80, 24);

    /** The cursor. */
    private final Point cursor = new Point();

    /** The antialiasing. */
    private boolean antialiasing = true;

    /** The emulator. */
    private Emulator emulator;

    /** The selection start. */
    private Point selectionStart;

    /** The selection end. */
    private Point selectionEnd;

    /** The selection in progress. */
    private boolean selectionInProgress;

    /** The clip board. */
    private Clipboard systemClipBoard;

    /** The clip board. */
    private Clipboard systemSelection;

    /** The resize panel delegate. */
    private ResizePanelDelegate resizePanelDelegate;

    /** The back buffer. */
    private final BackBuffer backBuffer;

    /** The scroll buffer. */
    private final ScrollBuffer scrollBuffer;

    /** The style state. */
    private final StyleState styleState;

    /** The brm. */
    private final BoundedRangeModel brm = new DefaultBoundedRangeModel(0, 80, 0, 80);

    /** The client scroll origin. */
    private int clientScrollOrigin;

    /** The new client scroll origin. */
    private volatile int newClientScrollOrigin;

    /** The should draw cursor. */
    private volatile boolean shouldDrawCursor = true;

    /** The key handler. */
    private KeyListener keyHandler;

    /** Selection Listeners. */
    private final List<SelectionListener> selectionListeners = new ArrayList<>();

    /**
     * Instantiates a new term panel.
     *
     * @param backBuffer
     *            the back buffer
     * @param scrollBuffer
     *            the scroll buffer
     * @param styleState
     *            the style state
     */
    public TermPanel(final BackBuffer backBuffer, final ScrollBuffer scrollBuffer, final StyleState styleState) {
        this.scrollBuffer = scrollBuffer;
        this.backBuffer = backBuffer;
        this.styleState = styleState;

        this.brm.setRangeProperties(0, this.termSize.height, -scrollBuffer.getLineCount(), this.termSize.height, false);

        this.normalFont = Font.decode("Monospaced-14");
        this.boldFont = this.normalFont.deriveFont(Font.BOLD);

        this.establishFontMetrics();

        this.setUpImages();
        this.setUpClipboard();
        this.setAntiAliasing(this.antialiasing);

        this.setPreferredSize(new Dimension(this.getPixelWidth(), this.getPixelHeight()));

        this.setFocusable(true);
        this.enableInputMethods(true);

        this.setFocusTraversalKeysEnabled(false);

        this.addMouseMotionListener(new MouseMotionAdapter() {

            @SuppressWarnings("synthetic-access")
            @Override
            public void mouseDragged(final MouseEvent e) {
                final Point charCoords = TermPanel.this.panelToCharCoords(e.getPoint());

                if (!TermPanel.this.selectionInProgress) {
                    TermPanel.this.selectionStart = new Point(charCoords);
                    TermPanel.this.selectionInProgress = true;
                }
                TermPanel.this.repaint();
                TermPanel.this.selectionEnd = charCoords;
                TermPanel.this.selectionEnd.x =
                    Math.min(TermPanel.this.selectionEnd.x + 1, TermPanel.this.termSize.width);
            }
        });

        this.addMouseListener(new MouseAdapter() {

            @SuppressWarnings({ "synthetic-access", "unused" })
            @Override
            public void mouseReleased(final MouseEvent e) {
                TermPanel.this.selectionInProgress = false;
                if (TermPanel.this.selectionStart != null && TermPanel.this.selectionEnd != null) {
                    TermPanel.this.copySelection(TermPanel.this.selectionStart, TermPanel.this.selectionEnd);
                    TermPanel.this.fireSelectionChanged();
                }
                TermPanel.this.repaint();
            }

            @SuppressWarnings("synthetic-access")
            @Override
            public void mouseClicked(final MouseEvent e) {
                TermPanel.this.requestFocusInWindow();
                TermPanel.this.selectionStart = null;
                TermPanel.this.selectionEnd = null;
                TermPanel.this.fireSelectionChanged();
                if (e.getButton() == MouseEvent.BUTTON3) {
                    TermPanel.this.pasteSelection();
                }
                TermPanel.this.repaint();
            }
        });

        this.addComponentListener(new ComponentAdapter() {

            @SuppressWarnings({ "synthetic-access", "unused" })
            @Override
            public void componentResized(final ComponentEvent e) {
                TermPanel.this.sizeTerminalFromComponent();
            }
        });

        this.brm.addChangeListener(new ChangeListener() {

            @SuppressWarnings({ "synthetic-access", "unused" })
            @Override
            public void stateChanged(final ChangeEvent e) {
                TermPanel.this.newClientScrollOrigin = TermPanel.this.brm.getValue();
            }
        });

        final Timer redrawTimer = new Timer((int) (1000 / FPS), new ActionListener() {

            @SuppressWarnings("unused")
            @Override
            public void actionPerformed(final ActionEvent e) {
                TermPanel.this.redrawFromDamage();
            }
        });
        this.setDoubleBuffered(true);
        redrawTimer.start();
        this.repaint();

    }

    /**
     * Panel to char coords.
     *
     * @param p
     *            the p
     * @return the point
     */
    private Point panelToCharCoords(final Point p) {
        return new Point(p.x / this.charSize.width, p.y / this.charSize.height + this.clientScrollOrigin);
    }

    /**
     * Sets the up clipboard.
     */
    void setUpClipboard() {
        this.systemSelection = Toolkit.getDefaultToolkit().getSystemSelection();
        this.systemClipBoard = Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    /**
     * Copy selection.
     *
     * @param csSelectionStart
     *            the cs_selection start
     * @param csSelectionEnd
     *            the cs_selection end
     * @param cb
     *            the Clipboard
     */
    private void copyClipboard(final Point csSelectionStart, final Point csSelectionEnd, final Clipboard cb) {

        if (csSelectionStart == null || csSelectionEnd == null) {
            return;
        }

        Point top;
        Point bottom;

        if (csSelectionStart.y == csSelectionEnd.y) {
            /* same line */
            top = csSelectionStart.x < csSelectionEnd.x ? csSelectionStart : csSelectionEnd;
            bottom = csSelectionStart.x >= csSelectionEnd.x ? csSelectionStart : csSelectionEnd;
        } else {
            top = csSelectionStart.y < csSelectionEnd.y ? csSelectionStart : csSelectionEnd;
            bottom = csSelectionStart.y > csSelectionEnd.y ? csSelectionStart : csSelectionEnd;
        }

        final StringBuffer selection = new StringBuffer();
        if (top.y < 0) {
            final Point scrollEnd = bottom.y >= 0 ? new Point(this.termSize.width, -1) : bottom;
            this.scrollBuffer.pumpRuns(top.y, scrollEnd.y - top.y, new SelectionRunConsumer(selection, top, scrollEnd));

        }

        if (bottom.y >= 0) {
            final Point backBegin = top.y < 0 ? new Point(0, 0) : top;
            this.backBuffer.pumpRuns(0, backBegin.y, this.termSize.width, bottom.y - backBegin.y + 1,
                new SelectionRunConsumer(selection, backBegin, bottom));
        }

        if (selection.length() == 0) {
            return;
        }

        try {
            cb.setContents(new StringSelection(selection.toString()), this);
        }
        catch (final IllegalStateException e) {
            this.log.error("Could not set clipboard:", e);
        }
    }

    /**
     * Copy selection.
     *
     * @param csSelectionStart
     *            the cs_selection start
     * @param csSelectionEnd
     *            the cs_selection end
     */
    private void copySelection(final Point csSelectionStart, final Point csSelectionEnd) {
        this.copyClipboard(csSelectionStart, csSelectionEnd,
            this.systemSelection != null ? this.systemSelection : this.systemClipBoard);
    }

    /**
     * Copy clipboard.
     *
     * @param csSelectionStart
     *            the cs_selection start
     * @param csSelectionEnd
     *            the cs_selection end
     */
    private void copyClipboard(final Point csSelectionStart, final Point csSelectionEnd) {
        this.copyClipboard(csSelectionStart, csSelectionEnd, this.systemClipBoard);
    }

    /**
     * Copy clipboard.
     */
    public void copyClipboard() {
        this.copyClipboard(this.selectionStart, this.selectionEnd);
    }

    /**
     * Paste selection.
     */
    public void pasteSelection() {
        if (this.systemSelection == null) {
            this.pasteClipboard();
        }
        try {
            final String selection = (String) this.systemSelection.getData(DataFlavor.stringFlavor);
            this.emulator.sendBytes(selection.getBytes());
        }
        catch (final UnsupportedFlavorException e) {
            this.log.debug("unsupported flavor in paste", e);
        }
        catch (final IOException e) {
            this.log.debug("I/O error in paste", e);
        }
    }

    /**
     * Paste selection.
     */
    public void pasteClipboard() {
        try {
            final String selection = (String) this.systemClipBoard.getData(DataFlavor.stringFlavor);
            this.emulator.sendBytes(selection.getBytes());
        }
        catch (final UnsupportedFlavorException e) {
            this.log.debug("unsupported flavor in paste", e);
        }
        catch (final IOException e) {
            this.log.debug("I/O error in paste", e);
        }
    }

    /*
     * Do not care
     */
    /*
     * (non-Javadoc)
     *
     * @see
     * java.awt.datatransfer.ClipboardOwner#lostOwnership(java.awt.datatransfer
     * .Clipboard, java.awt.datatransfer.Transferable)
     */
    /** {@inheritDoc} */
    @SuppressWarnings("unused")
    @Override
    public void lostOwnership(final Clipboard clipboard, final Transferable contents) {
        // .
    }

    /**
     * Sets the up images.
     */
    private void setUpImages() {
        final BufferedImage oldImage = this.img;
        this.img = new BufferedImage(this.getPixelWidth(), this.getPixelHeight(), BufferedImage.TYPE_INT_RGB);

        this.gfx = this.img.createGraphics();
        this.gfx.fillRect(0, 0, this.getPixelWidth(), this.getPixelHeight());

        if (oldImage != null) {
            this.gfx.drawImage(oldImage, 0, this.img.getHeight() - oldImage.getHeight(), oldImage.getWidth(),
                oldImage.getHeight(), this.termComponent);
        }
    }

    /**
     * Size terminal from component.
     */
    private void sizeTerminalFromComponent() {
        if (this.emulator != null) {
            final int newWidth = this.getWidth() / this.charSize.width;
            final int newHeight = this.getHeight() / this.charSize.height;
            if (newWidth <= 0 || newHeight <= 0) {
                return;
            }
            final Dimension newSize = new Dimension(newWidth, newHeight);

            this.emulator.postResize(newSize, RequestOrigin.User);
        }
    }

    /**
     * Sets the emulator.
     *
     * @param emulator
     *            the new emulator
     */
    public void setEmulator(final Emulator emulator) {
        this.emulator = emulator;
        this.sizeTerminalFromComponent();
    }

    /**
     * Sets the key handler.
     *
     * @param keyHandler
     *            the new key handler
     */
    public void setKeyHandler(final KeyListener keyHandler) {
        this.keyHandler = keyHandler;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.agilhard.terminal.emulation.TerminalDisplay#doResize(java.awt.Dimension,
     * net.agilhard.terminal.emulation.RequestOrigin)
     */
    /** {@inheritDoc} */
    @Override
    public Dimension doResize(final Dimension newSize, final RequestOrigin origin) {
        if (!newSize.equals(this.termSize)) {
            this.backBuffer.lock();
            try {
                this.backBuffer.doResize(newSize, origin);
                this.termSize = (Dimension) newSize.clone();
                // resize images..
                this.setUpImages();

                final Dimension pixelDimension = new Dimension(this.getPixelWidth(), this.getPixelHeight());

                this.setPreferredSize(pixelDimension);
                if (this.resizePanelDelegate != null) {
                    this.resizePanelDelegate.resizedPanel(pixelDimension, origin);
                }
                this.brm.setRangeProperties(0, this.termSize.height, -this.scrollBuffer.getLineCount(),
                    this.termSize.height, false);

            } finally {
                this.backBuffer.unlock();
            }
        }
        return new Dimension(this.getPixelWidth(), this.getPixelHeight());
    }

    /**
     * Sets the resize panel delegate.
     *
     * @param resizeDelegate
     *            the new resize panel delegate
     */
    public void setResizePanelDelegate(final ResizePanelDelegate resizeDelegate) {
        this.resizePanelDelegate = resizeDelegate;
    }

    /**
     * Establish font metrics.
     */
    private void establishFontMetrics() {
        final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setFont(this.normalFont);

        final FontMetrics fo = graphics.getFontMetrics();
        this.descent = fo.getDescent();
        this.charSize.width = fo.charWidth('@');
        this.charSize.height = fo.getHeight() + this.lineSpace * 2;
        this.descent += this.lineSpace;

        image.flush();
        graphics.dispose();
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.swing.JComponent#paintComponent(java.awt.Graphics)
     */
    /** {@inheritDoc} */
    @Override
    public void paintComponent(final Graphics g) {
        final Graphics2D g2d = (Graphics2D) g;
        super.paintComponent(g);
        if (this.img != null) {
            g2d.drawImage(this.img, 0, 0, this.termComponent);
            if (this.shouldDrawCursor) {
                this.drawCursor(g2d);
            }
            this.drawSelection(g2d);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.swing.JComponent#processKeyEvent(java.awt.event.KeyEvent)
     */
    /** {@inheritDoc} */
    @Override
    public void processKeyEvent(final KeyEvent e) {
        final int id = e.getID();
        if (id == KeyEvent.KEY_PRESSED) {
            final int mod = e.getModifiers();

            final char c = (char) e.getKeyCode();

            if ((mod & Event.ALT_MASK) > 0 && !((mod & Event.CTRL_MASK) > 0)
                && (c == 'T' || c == 'L' || c == 'O' || c == 'M' || c == 'S' || c == 'H' || c == 'D' || c == 'Q')
                || c == 'W' && (mod & Event.CTRL_MASK) > 0) {
                // reserve some alt and ctrl-w ctrl-shift-w for menu accelerators
                super.processKeyEvent(e);
                return;
            }

            if ((mod & Event.CTRL_MASK) > 0 && (mod & Event.SHIFT_MASK) > 0 && (c == 'C' || c == 'P')) {
                if (c == 'C') {
                    this.copyClipboard();
                } else {
                    this.pasteClipboard();
                }

            } else if (this.keyHandler != null) {
                this.keyHandler.keyPressed(e);
            }
            // }else if (id == KeyEvent.KEY_RELEASED) {
            /* keyReleased(e); */
        } else if (id == KeyEvent.KEY_TYPED) {
            if (this.keyHandler != null) {
                this.keyHandler.keyTyped(e);
            }
        }
        e.consume();
    }

    /**
     * Gets the pixel width.
     *
     * @return the pixel width
     */
    public int getPixelWidth() {
        return this.charSize.width * this.termSize.width;
    }

    /**
     * Gets the pixel height.
     *
     * @return the pixel height
     */
    public int getPixelHeight() {
        return this.charSize.height * this.termSize.height;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.agilhard.terminal.emulation.TerminalDisplay#getColumnCount()
     */
    /** {@inheritDoc} */
    @Override
    public int getColumnCount() {
        return this.termSize.width;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.agilhard.terminal.emulation.TerminalDisplay#getRowCount()
     */
    /** {@inheritDoc} */
    @Override
    public int getRowCount() {
        return this.termSize.height;
    }

    /**
     * Draw cursor.
     *
     * @param g
     *            the g
     */
    public void drawCursor(final Graphics2D g) {
        final int y = this.cursor.y - 1 - this.clientScrollOrigin;
        if (y >= 0 && y < this.termSize.height) {
            final Style current = this.styleState.getCurrent();
            g.setColor(current.getForeground());
            g.setXORMode(current.getBackground());
            g.fillRect(this.cursor.x * this.charSize.width, y * this.charSize.height, this.charSize.width,
                this.charSize.height);
        }
    }

    /**
     * Draw selection.
     *
     * @param g
     *            the g
     */
    public void drawSelection(final Graphics2D g) {
        /* which is the top one */
        Point top;
        Point bottom;
        final Style current = this.styleState.getCurrent();
        g.setColor(current.getForeground());
        g.setXORMode(current.getBackground());
        if (this.selectionStart == null || this.selectionEnd == null) {
            return;
        }

        if (this.selectionStart.y == this.selectionEnd.y) {
            /* same line */
            if (this.selectionStart.x == this.selectionEnd.x) {
                return;
            }
            top = this.selectionStart.x < this.selectionEnd.x ? this.selectionStart : this.selectionEnd;
            bottom = this.selectionStart.x >= this.selectionEnd.x ? this.selectionStart : this.selectionEnd;

            g.fillRect(top.x * this.charSize.width, (top.y - this.clientScrollOrigin) * this.charSize.height,
                (bottom.x - top.x) * this.charSize.width, this.charSize.height);

        } else {
            top = this.selectionStart.y < this.selectionEnd.y ? this.selectionStart : this.selectionEnd;
            bottom = this.selectionStart.y > this.selectionEnd.y ? this.selectionStart : this.selectionEnd;
            /* to end of first line */
            g.fillRect(top.x * this.charSize.width, (top.y - this.clientScrollOrigin) * this.charSize.height,
                (this.termSize.width - top.x) * this.charSize.width, this.charSize.height);

            if (bottom.y - top.y > 1) {
                /* intermediate lines */
                g.fillRect(0, (top.y + 1 - this.clientScrollOrigin) * this.charSize.height,
                    this.termSize.width * this.charSize.width, (bottom.y - top.y - 1) * this.charSize.height);
            }

            /* from beginning of last line */

            g.fillRect(0, (bottom.y - this.clientScrollOrigin) * this.charSize.height, bottom.x * this.charSize.width,
                this.charSize.height);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see net.agilhard.terminal.emulation.StyledRunConsumer#consumeRun(int, int,
     * net.agilhard.terminal.emulation.Style, char[], int, int)
     */
    /** {@inheritDoc} */
    @Override
    public void consumeRun(final int x, final int y, final Style style, final char[] buf, final int start,
        final int len) {
        this.gfx.setColor(style.getBackgroundForRun());
        this.gfx.fillRect(x * this.charSize.width, (y - this.clientScrollOrigin) * this.charSize.height,
            len * this.charSize.width, this.charSize.height);

        this.gfx.setFont(style.hasOption(Style.Option.BOLD) ? this.boldFont : this.normalFont);
        this.gfx.setColor(style.getForegroundForRun());

        final int baseLine = (y + 1 - this.clientScrollOrigin) * this.charSize.height - this.descent;
        this.gfx.drawChars(buf, start, len, x * this.charSize.width, baseLine);
        if (style.hasOption(Style.Option.UNDERSCORE)) {
            this.gfx.drawLine(x * this.charSize.width, baseLine + 1, (x + len) * this.charSize.width, baseLine + 1);
        }
    }

    /**
     * Client scroll origin changed.
     *
     * @param oldOrigin
     *            the old origin
     */
    private void clientScrollOriginChanged(final int oldOrigin) {
        final int dy = this.clientScrollOrigin - oldOrigin;

        final int dyPix = dy * this.charSize.height;

        this.gfx.copyArea(0, Math.max(0, dyPix), this.getPixelWidth(), this.getPixelHeight() - Math.abs(dyPix), 0,
            -dyPix);

        if (dy < 0) {
            // Scrolling up; Copied down
            // New area at the top to be filled in - can only be from scroll
            // buffer
            //

            this.scrollBuffer.pumpRuns(this.clientScrollOrigin, -dy, this);
        } else {
            // Scrolling down; Copied up
            // New area at the bottom to be filled - can be from both

            final int oldEnd = oldOrigin + this.termSize.height;

            // Either its the whole amount above the back buffer + some more
            // Or its the whole amount we moved
            // Or we are already out of the scroll buffer
            final int portionInScroll = oldEnd < 0 ? Math.min(-oldEnd, dy) : 0;

            final int portionInBackBuffer = dy - portionInScroll;

            if (portionInScroll > 0) {
                this.scrollBuffer.pumpRuns(oldEnd, portionInScroll, this);
            }

            if (portionInBackBuffer > 0) {
                this.backBuffer.pumpRuns(0, oldEnd + portionInScroll, this.termSize.width, portionInBackBuffer, this);
            }

        }

    }

    /** The no damage. */
    private int noDamage;

    /** The frames skipped. */
    private int framesSkipped;

    /** The cursor changed. */
    private boolean cursorChanged;

    /**
     * Redraw from damage.
     */
    public void redrawFromDamage() {

        final int newOrigin = this.newClientScrollOrigin;
        if (!this.backBuffer.tryLock()) {
            if (this.framesSkipped >= 5) {
                this.backBuffer.lock();
            } else {
                this.framesSkipped++;
                return;
            }
        }
        try {
            this.framesSkipped = 0;

            final boolean serverScroll =
                this.pendingScrolls.enact(this.gfx, this.getPixelWidth(), this.charSize.height);

            final boolean clientScroll = this.clientScrollOrigin != newOrigin;
            if (clientScroll) {
                final int oldOrigin = this.clientScrollOrigin;
                this.clientScrollOrigin = newOrigin;
                this.clientScrollOriginChanged(oldOrigin);
            }

            final boolean hasDamage = this.backBuffer.hasDamage();
            if (hasDamage) {
                this.noDamage = 0;

                this.backBuffer.pumpRunsFromDamage(this);
                this.backBuffer.resetDamage();
            } else {
                this.noDamage++;
            }

            if (serverScroll || clientScroll || hasDamage || this.cursorChanged) {
                this.repaint();
                this.cursorChanged = false;
            }
        } finally {
            this.backBuffer.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see net.agilhard.terminal.emulation.TerminalDisplay#scrollArea(int, int, int)
     */
    /** {@inheritDoc} */
    @Override
    public void scrollArea(final int y, final int h, final int dy) {
        if (dy < 0) {
            // Moving lines off the top of the screen
            // TODO: Something to do with application keypad mode
            // TODO: Something to do with the scroll margins
            this.backBuffer.pumpRuns(0, y - 1, this.termSize.width, -dy, this.scrollBuffer);

            this.brm.setRangeProperties(0, this.termSize.height, -this.scrollBuffer.getLineCount(),
                this.termSize.height, false);
        }
        this.selectionStart = null;
        this.selectionEnd = null;
        this.pendingScrolls.add(y, h, dy);
    }

    /**
     * The Class PendingScrolls.
     */
    static class PendingScrolls {

        /** The ys. */
        private int[] ys = new int[10];

        /** The hs. */
        private int[] hs = new int[10];

        /** The dys. */
        private int[] dys = new int[10];

        /** The scroll count. */
        private int scrollCount = -1;

        /**
         * Ensure arrays.
         *
         * @param index
         *            the index
         */
        void ensureArrays(final int index) {
            final int curLen = this.ys.length;
            if (index >= curLen) {
                this.ys = Util.copyOf(this.ys, curLen * 2);
                this.hs = Util.copyOf(this.hs, curLen * 2);
                this.dys = Util.copyOf(this.dys, curLen * 2);
            }
        }

        /**
         * Adds the.
         *
         * @param y
         *            the y
         * @param h
         *            the h
         * @param dy
         *            the dy
         */
        void add(final int y, final int h, final int dy) {
            if (dy == 0) {
                return;
            }
            if (this.scrollCount >= 0 && y == this.ys[this.scrollCount] && h == this.hs[this.scrollCount]) {
                this.dys[this.scrollCount] += dy;
            } else {
                this.scrollCount++;
                this.ensureArrays(this.scrollCount);
                this.ys[this.scrollCount] = y;
                this.hs[this.scrollCount] = h;
                this.dys[this.scrollCount] = dy;
            }
        }

        /**
         * Enact.
         *
         * @param gfx
         *            the gfx
         * @param width
         *            the width
         * @param charHeight
         *            the char height
         * @return true, if successful
         */
        boolean enact(final Graphics2D gfx, final int width, final int charHeight) {
            if (this.scrollCount < 0) {
                return false;
            }
            for (int i = 0; i <= this.scrollCount; i++) {
                gfx.copyArea(0, this.ys[i] * charHeight, width, this.hs[i] * charHeight, 0, this.dys[i] * charHeight);
            }
            this.scrollCount = -1;
            return true;
        }
    }

    /** The pending scrolls. */
    private final PendingScrolls pendingScrolls = new PendingScrolls();

    /*
     * (non-Javadoc)
     *
     * @see net.agilhard.terminal.emulation.TerminalDisplay#setCursor(int, int)
     */
    /** {@inheritDoc} */
    @Override
    public void setCursor(final int x, final int y) {
        this.cursor.x = x;
        this.cursor.y = y;
        this.cursorChanged = true;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.agilhard.terminal.emulation.TerminalDisplay#beep()
     */
    /** {@inheritDoc} */
    @Override
    public void beep() {
        Toolkit.getDefaultToolkit().beep();
    }

    /**
     * Sets the line space.
     *
     * @param foo
     *            the new line space
     */
    public void setLineSpace(final int foo) {
        this.lineSpace = foo;
    }

    /**
     * Sets the anti aliasing.
     *
     * @param foo
     *            the new anti aliasing
     */
    public void setAntiAliasing(final boolean foo) {
        if (this.gfx == null) {
            return;
        }
        this.antialiasing = foo;
        final java.lang.Object mode =
            foo ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;
        final RenderingHints hints = new RenderingHints(RenderingHints.KEY_TEXT_ANTIALIASING, mode);
        this.gfx.setRenderingHints(hints);
    }

    /**
     * Gets the bounded range model.
     *
     * @return the bounded range model
     */
    public BoundedRangeModel getBoundedRangeModel() {
        return this.brm;
    }

    /**
     * Gets the term back buffer.
     *
     * @return the term back buffer
     */
    public BackBuffer getTermBackBuffer() {
        return this.backBuffer;
    }

    /**
     * Gets the scroll buffer.
     *
     * @return the scroll buffer
     */
    public ScrollBuffer getScrollBuffer() {
        return this.scrollBuffer;
    }

    /**
     * Lock.
     */
    public void lock() {
        this.backBuffer.lock();
    }

    /**
     * Unlock.
     */
    public void unlock() {
        this.backBuffer.unlock();
    }

    /**
     * Fire selection changed.
     */
    public void fireSelectionChanged() {
        if (this.selectionListeners == null || this.selectionListeners.size() == 0) {
            return;
        }
        for (final SelectionListener listener : this.selectionListeners) {
            listener.selectionChanged(this.selectionStart, this.selectionEnd);
        }
    }

    /**
     * Adds the selection listener.
     *
     * @param listener
     *            the listener
     */
    public void addSelectionListener(final SelectionListener listener) {
        if (this.selectionListeners == null) {
            return;
        }
        this.selectionListeners.add(listener);
    }

    /**
     * Removes the selection listener.
     *
     * @param listener
     *            the listener
     */
    public void removeSelectionListener(final SelectionListener listener) {
        if (this.selectionListeners == null) {
            return;
        }
        this.selectionListeners.remove(listener);
    }

    /**
     * Checks if is should draw cursor.
     *
     * @return the shouldDrawCursor
     */
    public boolean isShouldDrawCursor() {
        return this.shouldDrawCursor;
    }

    /**
     * Sets the should draw cursor.
     *
     * @param shouldDrawCursor
     *            the shouldDrawCursor to set
     */
    public void setShouldDrawCursor(final boolean shouldDrawCursor) {
        this.shouldDrawCursor = shouldDrawCursor;
    }

    /**
     * Gets the term size.
     *
     * @return the termSize
     */
    public Dimension getTermSize() {
        return this.termSize;
    }

    /**
     * Gets the selection start.
     *
     * @return the selectionStart
     */
    public Point getSelectionStart() {
        return this.selectionStart;
    }

    /**
     * Gets the selection end.
     *
     * @return the selectionEnd
     */
    public Point getSelectionEnd() {
        return this.selectionEnd;
    }

    /**
     * Gets the no damage.
     *
     * @return the noDamage
     */
    public int getNoDamage() {
        return this.noDamage;
    }

}
