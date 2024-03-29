/*******************************************************************************
 * Copyright (c) 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - Initial API and implementation
 *******************************************************************************/
package org.eclipse.remote.internal.jsch.ui;

import java.net.PasswordAuthentication;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jsch.ui.UserInfoPrompter;
import org.eclipse.remote.core.IRemoteConnection;
import org.eclipse.remote.core.IUserAuthenticator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;

public class JSchUserAuthenticator implements IUserAuthenticator {
	private UserInfoPrompter prompter;

	public JSchUserAuthenticator(IRemoteConnection conn) {
		try {
			prompter = new UserInfoPrompter(new JSch().getSession(conn.getUsername(), conn.getAddress()));
		} catch (JSchException e) {
			// Not allowed
		}
	}

	@Override
	public PasswordAuthentication prompt(String username, String message) {
		if (prompter.promptPassword(message)) {
			String sessionUserName = prompter.getSession().getUserName();
			if (sessionUserName != null) {
				username = sessionUserName;
			}
			PasswordAuthentication auth = new PasswordAuthentication(username, prompter.getPassword().toCharArray());
			return auth;
		}
		return null;
	}

	@Override
	public String[] prompt(String destination, String name, String message, String[] prompt, boolean[] echo) {
		return prompter.promptKeyboardInteractive(destination, name, message, prompt, echo);
	}

	@Override
	public int prompt(final int promptType, final String title, final String message, final int[] promptResponses,
			final int defaultResponseIndex) {
		final Display display = getDisplay();
		final int[] retval = new int[1];
		final String[] buttons = new String[promptResponses.length];
		for (int i = 0; i < promptResponses.length; i++) {
			int prompt = promptResponses[i];
			switch (prompt) {
			case IDialogConstants.OK_ID:
				buttons[i] = IDialogConstants.OK_LABEL;
				break;
			case IDialogConstants.CANCEL_ID:
				buttons[i] = IDialogConstants.CANCEL_LABEL;
				break;
			case IDialogConstants.NO_ID:
				buttons[i] = IDialogConstants.NO_LABEL;
				break;
			case IDialogConstants.YES_ID:
				buttons[i] = IDialogConstants.YES_LABEL;
				break;
			}
		}

		display.syncExec(new Runnable() {
			@Override
			public void run() {
				final MessageDialog dialog = new MessageDialog(new Shell(display), title, null /* title image */, message,
						promptType, buttons, defaultResponseIndex);
				retval[0] = dialog.open();
			}
		});
		return promptResponses[retval[0]];
	}

	private Display getDisplay() {
		Display display = Display.getCurrent();
		if (display == null) {
			display = Display.getDefault();
		}
		return display;
	}
}
