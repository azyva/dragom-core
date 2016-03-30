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

package org.azyva.dragom.execcontext.support;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ToolLifeCycleExecContext;
import org.azyva.dragom.execcontext.plugin.impl.DefaultWorkspacePluginFactory;
import org.azyva.dragom.util.RuntimeExceptionUserError;


/**
 * Holds and provides access to the {@link ExecContext} during the execution of
 * tools.
 * <p>
 * In Java global variables must be defined within a class. That is what we need
 * here as we want the tools to have access to the ExecContext globally. An
 * alternative would have been to introduce an ExecContext parameter in many if
 * not all methods so that it is available everywhere. But this would have been
 * cumbersome.
 * <p>
 * Dragom tools are meant to be single-threaded, but thread-safe, meaning that
 * multiple independent instances of such tools should be allowed to execute
 * simultaneously within a single JVM if so desired (within a
 * <a href="http://www.martiansoftware.com/nailgun/">NaigGun<a> server for
 * example). Therefore, the ExecContext is held in thread-local storage instead of
 * in a truly global static variable. If a tool is multi-threaded, it is its
 * responsibility to copy the ExecContext from one thread to the other using
 * {@link #setSecondaryThread}.
 * <p>
 * This class is not meant to be swappable. Tools refer directly to it. It is the
 * responsibility of the initialization phase of tools to set the desired
 * ExecContext instance within this class using {@link #setAndStartTool}.
 * Thereafter during the execution of tools only the method get is expected to be
 * used to obtain the ambient ExecContext.
 * <p>
 * setAndStartTool registers the fact that the ExecContext is being used by a tool
 * and if the same ExecContext is already being used by a tool (another tool
 * instance in another thread) an exception is raised.
 * <p>
 * When a tool finishes using an ExecContext, {@link #endToolAndUnset} must
 * therefore be called to register the fact that it (the current ExecContext
 * stored in thread-local storage) is not being used anymore. A try/finally
 * construct is generally required for that purpose.
 *
 * @author David Raymond
 */
public class ExecContextHolder {
	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_EXEC_CONTEXT_LOCKED = "EXEC_CONTEXT_LOCKED";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(DefaultWorkspacePluginFactory.class.getName() + "ResourceBundle");

	/**
	 * Thread-local holder variable for the ExecContext.
	 */
	private static ThreadLocal<ExecContext> threadLocalExecContext = new ThreadLocal<ExecContext>();

	/**
	 * Set of ExecContext currently being used.
	 */
	private static Set<ExecContext> setExecContextLocked = new HashSet<ExecContext>();

	/**
	 * Sets the {@link ExecContext} in thread-local storage and starts tool
	 * execution.
	 * <p>
	 * To be called during the initialization phase of a tool.
	 * <p>
	 * If the ExecContext implementation implements {@link ToolLifeCycleExecContext}
	 * {@link ToolLifeCycleExecContext#startTool} is called.
	 *
	 * @param execContext ExecContext.
	 * @param propertiesInit Initialization properties specific to the tool.
	 */
	public static void setAndStartTool(ExecContext execContext, Properties propertiesInit) {
		if (ExecContextHolder.setExecContextLocked.contains(execContext)) {
			throw new RuntimeExceptionUserError(MessageFormat.format(ExecContextHolder.resourceBundle.getString(ExecContextHolder.MSG_PATTERN_KEY_EXEC_CONTEXT_LOCKED), execContext.getName()));
		}

		ExecContextHolder.threadLocalExecContext.set(execContext);
		ExecContextHolder.setExecContextLocked.add(execContext);

		if (execContext instanceof ToolLifeCycleExecContext) {
			ToolLifeCycleExecContext toolLifeCycleExecContext;

			toolLifeCycleExecContext = (ToolLifeCycleExecContext)execContext;

			toolLifeCycleExecContext.startTool(propertiesInit);
		}
	}

	/**
	 * Sets the {@link ExecContext} in thread-local storage for a secondary
	 * thread for an already started tool.
	 * <p>
	 * To be called when a tool is multi-threaded so that each thread gets its own
	 * ExecContext, without having {@link ToolLifeCycleExecContext#startTool} called.
	 *
	 * @param execContext ExecContext. Must be one for which
	 *   {@link #setAndStartTool} has already been called.
	 */
	public static void setSecondaryThread(ExecContext execContext) {
		if (!ExecContextHolder.setExecContextLocked.contains(execContext)) {
			throw new RuntimeException("ExecContext is not currenly being used.");
		}

		ExecContextHolder.threadLocalExecContext.set(execContext);
	}

	/**
	 * Ends tool execution and unsets the {@link ExecContext} from thread-local
	 * storage.
	 * <p>
	 * The ExecContext is the one that was set using {@link #setAndStartTool}.
	 * <p>
	 * To be called during the termination phase of a tool. Should be called on the
	 * same thread as setAndStartTool.
	 * <p>
	 * If the ExecContext implementation implements {@link ToolLifeCycleExecContext}
	 * {@link ToolLifeCycleExecContext#endTool} is called.
	 */
	public static void endToolAndUnset() {
		ExecContext execContext;

		execContext = ExecContextHolder.get();

		if (execContext != null) {
			if (execContext instanceof ToolLifeCycleExecContext) {
				ToolLifeCycleExecContext toolLifeCycleExecContext;

				toolLifeCycleExecContext = (ToolLifeCycleExecContext)execContext;

				toolLifeCycleExecContext.endTool();
			}

			ExecContextHolder.setExecContextLocked.remove(execContext);
			ExecContextHolder.threadLocalExecContext.set(null);
		}
	}

	/**
	 * Force-unsets an {@link ExecContext}. Used as a last resort after a tool
	 * ends abnormally, leaving the ExecContext in the used state.
	 * <p>
	 * The ExecContext cannot be obtained with {@link ExecContext#get} since this
	 * method is generally not called on the same thread as the one that previously
	 * set the ExecContext without releasing it.
	 *
	 * @param execContext ExecContext.
	 */
	public static void forceUnset(ExecContext execContext) {
		if (execContext instanceof ToolLifeCycleExecContext) {
			ToolLifeCycleExecContext toolLifeCycleExecContext;

			toolLifeCycleExecContext = (ToolLifeCycleExecContext)execContext;

			toolLifeCycleExecContext.endTool();
		}

		ExecContextHolder.setExecContextLocked.remove(execContext);
	}

	/**
	 * Returns the {@link ExecContext} stored in thread-local storage.
	 * <p>
	 * To be called during the execution of tools. Essentially any class (model,
	 * plugin, etc.) can call this method to have access to the ExecContext.
	 *
	 * @return See description.
	 */
	public static ExecContext get() {
		return ExecContextHolder.threadLocalExecContext.get();
	}
}
