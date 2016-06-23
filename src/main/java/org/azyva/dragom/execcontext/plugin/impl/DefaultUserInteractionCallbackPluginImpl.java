/*
 * Copyright 2015 AZYVA INC.
 *
 * This file is part of Dragom.
 *
 * Dragom is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Dragom is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Dragom.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.azyva.dragom.execcontext.plugin.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.Stack;

import org.apache.commons.lang.WordUtils;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultUserInteractionCallbackPluginImpl implements UserInteractionCallbackPlugin {
	private static final Logger logger = LoggerFactory.getLogger(DefaultUserInteractionCallbackPluginImpl.class);

	/**
	 * Runtime property indicating that no information must be obtained from the user.
	 */
	private static final String RUNTIME_PROPERTY_IND_BATCH_MODE = "IND_BATCH_MODE";

	/**
	 * Runtime property specifying the indentation for each bracket level.
	 */
	private static final String RUNTIME_PROPERTY_BRACKET_INDENT = "BRACKET_INDENT";

	/**
	 * Default indentation for each bracket level when the runtime property
	 * BRACKET_INDENT is not defined.
	 */
	private static final int DEFAULT_BRACKET_INDENT = 4;

	/**
	 * Runtime property specifying the WrapMode.
	 */
	private static final String RUNTIME_PROPERTY_WRAP_MODE = "WRAP_MODE";

	/**
	 * Runtime property specifying the Wrap width.
	 */
	private static final String RUNTIME_PROPERTY_WRAP_WIDTH = "WRAP_WIDTH";

	/**
	 * Default wrap width when the runtime property WRAP_WIDTH is not defined.
	 */
	private static final int DEFAULT_WRAP_WIDTH = 132;

	/**
	 * Indicates that no information must be obtained from the user.
	 */
	boolean indBatchMode;

	/**
	 * Indentation for each bracket level.
	 */
	int bracketIndent;

	/**
	 * String of this.bracketIndent spaces.
	 */
	char arrayCharBracketIndent[];

	/**
	 * Defines the wrap modes.
	 * <p>
	 * Whatever the wrap mode, wrapping may also be performed by console which would
	 * render class controlled wrapping less useful.
	 */
	private enum WrapMode {
		/**
		 * No wrap.
		 * <p>
		 * Wrapping will be performed by console.
		 */
		NO_WRAP,

		/**
		 * Wrap to the specified width including the indent. Indented sections will have
		 * less available width for the actual text, but the whole output will be indented
		 * uniformly.
		 */
		WRAP_WITH_INDENT,

		/**
		 * Wrap to the specified width not including the indent. All sections regardless
		 * of indent will have the same available with for the actual text and global
		 * width of the whole output will increase with the indent.
		 */
		WRAP_WITHOUT_INDENT
	}

	/**
	 * WrapMode.
	 */
	private WrapMode wrapMode;

	/**
	 * Wrap width.
	 */
	private int wrapWidth;

	/**
	 * Opaque handle to a bracket.
	 *
	 * @author David Raymond
	 */
	private class BracketHandleImpl implements BracketHandle {
		private BracketHandleImpl() {
		}

		@Override
		public void close() {
			if (DefaultUserInteractionCallbackPluginImpl.this.stackBracketHandle.pop() != this) {
				throw new RuntimeException("Incorrect bracketing.");
			}
		}
	}

	/**
	 * Stack of {@link BracketHandle}'s.
	 * <p>
	 * The depth of the Stack defines the indentation levels.
	 * <p>
	 * Proper bracketing is validated by ensuring that a BracketHandle being closed
	 * is at the top of the Stack.
	 */
	private Stack<BracketHandle> stackBracketHandle;

	/**
	 * Active {@link WriterInfo}.
	 */
	WriterInfo writerInfoActive;

	/**
	 * Used to read information from stdin.
	 */
	BufferedReader bufferedReaderStdin;

	private class WriterInfo extends BufferedWriter {
		boolean indClosed;

		public WriterInfo(Writer writer) {
			super(writer);
		}

		@Override
		public void close() throws IOException {
			super.flush();

			if (!this.indClosed) {
				this.indClosed = true;
				DefaultUserInteractionCallbackPluginImpl.this.writerInfoActive = null;
			}
		}

		@Override
		public void write(int c) throws IOException {
			if (this.indClosed) {
				throw new RuntimeException("WriterInfo already closed.");
			}

			super.write(c);
		}

		@Override
		public void write(char[] cbuf, int off, int len) throws IOException {
			if (this.indClosed) {
				throw new RuntimeException("WriterInfo already closed.");
			}

			super.write(cbuf, off, len);
		}

		@Override
		public void write(String s, int off, int len) throws IOException {
			if (this.indClosed) {
				throw new RuntimeException("WriterInfo already closed.");
			}

			super.write(s, off, len);
		}

		@Override
		public void newLine() throws IOException {
			if (this.indClosed) {
				throw new RuntimeException("WriterInfo already closed.");
			}

			super.newLine();
		}

		@Override
		public void flush() throws IOException {
			if (this.indClosed) {
				throw new RuntimeException("WriterInfo already closed.");
			}

			super.flush();
		}
	}

	/**
	 * Default constructor.
	 *
	 * @param execContext ExecContext.
	 */
	public DefaultUserInteractionCallbackPluginImpl(ExecContext execContext) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		String runtimeProperty;

		this.bufferedReaderStdin = new BufferedReader(new InputStreamReader(System.in));

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		this.indBatchMode = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(null, DefaultUserInteractionCallbackPluginImpl.RUNTIME_PROPERTY_IND_BATCH_MODE));

		runtimeProperty = runtimePropertiesPlugin.getProperty(null, DefaultUserInteractionCallbackPluginImpl.RUNTIME_PROPERTY_BRACKET_INDENT);

		if (runtimeProperty == null) {
			this.bracketIndent = DefaultUserInteractionCallbackPluginImpl.DEFAULT_BRACKET_INDENT;
		} else {
			this.bracketIndent = Integer.parseInt(runtimeProperty);
		}

		this.arrayCharBracketIndent = new char[this.bracketIndent];
		Arrays.fill(this.arrayCharBracketIndent, ' ');

		this.stackBracketHandle = new Stack<BracketHandle>();

		runtimeProperty = runtimePropertiesPlugin.getProperty(null, DefaultUserInteractionCallbackPluginImpl.RUNTIME_PROPERTY_WRAP_MODE);

		if (runtimeProperty != null) {
			this.wrapMode = WrapMode.valueOf(runtimeProperty);

			runtimeProperty = runtimePropertiesPlugin.getProperty(null, DefaultUserInteractionCallbackPluginImpl.RUNTIME_PROPERTY_WRAP_WIDTH);

			if (runtimeProperty == null) {
				this.wrapWidth = DefaultUserInteractionCallbackPluginImpl.DEFAULT_WRAP_WIDTH;
			} else {
				this.wrapWidth = Integer.parseInt(runtimeProperty);
			}
		} else {
			this.wrapMode = WrapMode.NO_WRAP;
		}
	}

	@Override
	public BracketHandle startBracket(String info) {
		BracketHandle bracketHandle;

		bracketHandle = new BracketHandleImpl();

		this.stackBracketHandle.push(bracketHandle);

		this.provideInfo(info);

		return bracketHandle;
	}

	@Override
	public void provideInfo(String info) {
		if (this.writerInfoActive != null) {
			throw new RuntimeException("A WriterInfo is already active and has not been closed.");
		}

		System.out.println();
		this.printWithIndent(info);
		DefaultUserInteractionCallbackPluginImpl.logger.info("Information provided to user: " + info);
	}

	@Override
	public Writer provideInfoWithWriter(String info) {
		this.provideInfo(info);

		return new WriterInfo(new OutputStreamWriter(System.out));
	}

	@Override
	public String getInfo(String prompt) {
		String info;

		if (this.writerInfoActive != null) {
			throw new RuntimeException("A WriterInfo is already active and has not been closed.");
		}

		this.validateBatchMode(prompt);

		System.out.println();
		System.out.println();
		this.printWithIndent("##### Information request #####");
		this.printWithIndent(prompt);
		DefaultUserInteractionCallbackPluginImpl.logger.info("Information requested from user: " + prompt);

		try {
			info = this.bufferedReaderStdin.readLine();
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		System.out.println();

		DefaultUserInteractionCallbackPluginImpl.logger.info("Information returned by user: " + info);

		return info;
	}

	@Override
	public String getInfoWithDefault(String prompt, String defaultValue) {
		String info;

		if (this.writerInfoActive != null) {
			throw new RuntimeException("A WriterInfo is already active and has not been closed.");
		}

		this.validateBatchMode(prompt);

		System.out.println();
		System.out.println();
		this.printWithIndent("##### Information request #####");
		this.printWithIndent(prompt);
		DefaultUserInteractionCallbackPluginImpl.logger.info("Information requested from user: " + prompt);


		try {
			info = this.bufferedReaderStdin.readLine();
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		System.out.println();

		if (info.length() == 0) {
			DefaultUserInteractionCallbackPluginImpl.logger.info("Information returned by default: " + info);
			return defaultValue;
		} else {
			DefaultUserInteractionCallbackPluginImpl.logger.info("Information returned by user: " + info);
			return info;
		}
	}

	private void validateBatchMode(String message) {
		if (this.indBatchMode) {
			throw new RuntimeExceptionUserError("Information is requested and batch mode is endabled. Information request message: " + message);
		}
	}

	private void printWithIndent(String string) {
		String[] arrayLine = null;

		switch (this.wrapMode) {
		case NO_WRAP:
			break;

		case WRAP_WITH_INDENT:
			string = WordUtils.wrap(string, this.wrapWidth - (this.stackBracketHandle.size() * this.bracketIndent), "\n", false);
			break;

		case WRAP_WITHOUT_INDENT:
			string = WordUtils.wrap(string, this.wrapWidth, "\n", false);
			break;
		}

		// For the calls to WordUtils.warp above, if we don't specify the newline string,
		// the platform newline separator is used. Forcing the single character "\n" makes
		// it easier to make to portable.

		arrayLine = string.split("\n");

		for (int i = 0; i < arrayLine.length; i++) {
			for (int j = 0 ; j < this.stackBracketHandle.size(); j++) {
				System.out.print(this.arrayCharBracketIndent);
			}

			System.out.println(arrayLine[i]);
		}
	}
}
