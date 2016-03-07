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
	private static final String RUNTIME_PROPERTY_BATCH_MODE = "BATCH_MODE";

	/**
	 * Runtime property specifying the indentation for each bracket level.
	 */
	private static final String RUNTIME_PROPERTY_BRACKET_INDENT = "BRACKET_INDENT";

	/**
	 * Default indentation for each bracket level when the runtime property BRACKET
	 * INDENT is not defined.
	 */
	private static final int DEFAULT_BRACKET_INDENT = 4;

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
	char tabCharBracketIndent[];

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
		String stringBracketIndent;

		this.bufferedReaderStdin = new BufferedReader(new InputStreamReader(System.in));

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		this.indBatchMode = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(null, DefaultUserInteractionCallbackPluginImpl.RUNTIME_PROPERTY_BATCH_MODE));

		stringBracketIndent = runtimePropertiesPlugin.getProperty(null, DefaultUserInteractionCallbackPluginImpl.RUNTIME_PROPERTY_BRACKET_INDENT);

		if (stringBracketIndent == null) {
			this.bracketIndent = DefaultUserInteractionCallbackPluginImpl.DEFAULT_BRACKET_INDENT;
		} else {
			this.bracketIndent = Integer.parseInt(stringBracketIndent);
		}

		this.tabCharBracketIndent = new char[this.bracketIndent];
		Arrays.fill(this.tabCharBracketIndent, ' ');

		this.stackBracketHandle = new Stack<BracketHandle>();
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
		for (int i = 0 ; i < this.stackBracketHandle.size(); i++) {
			System.out.print(this.tabCharBracketIndent);
		}

		// TODO: May want to wrap using commons lang WordUtils.wrap.
		System.out.println(string);
	}
}
