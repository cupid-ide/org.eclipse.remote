/*******************************************************************************
 * Copyright (c) 2007, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - Initial API and implementation
 *******************************************************************************/
package org.eclipse.remote.internal.jsch.core;

import java.io.IOException;
import java.net.PasswordAuthentication;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jsch.core.IJSchService;
import org.eclipse.osgi.util.NLS;
import org.eclipse.remote.core.IRemoteConnection;
import org.eclipse.remote.core.IRemoteConnectionChangeEvent;
import org.eclipse.remote.core.IRemoteConnectionChangeListener;
import org.eclipse.remote.core.IRemoteConnectionWorkingCopy;
import org.eclipse.remote.core.IRemoteFileManager;
import org.eclipse.remote.core.IRemoteProcess;
import org.eclipse.remote.core.IRemoteProcessBuilder;
import org.eclipse.remote.core.IRemoteServices;
import org.eclipse.remote.core.IUserAuthenticator;
import org.eclipse.remote.core.exception.AddressInUseException;
import org.eclipse.remote.core.exception.RemoteConnectionException;
import org.eclipse.remote.core.exception.UnableToForwardPortException;
import org.eclipse.remote.internal.jsch.core.commands.ExecCommand;
import org.eclipse.remote.internal.jsch.core.messages.Messages;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

/**
 * @since 5.0
 */
public class JSchConnection implements IRemoteConnection {
	/**
	 * Class to supply credentials from connection attributes without user interaction.
	 */
	private class JSchUserInfo implements UserInfo, UIKeyboardInteractive {
		private boolean firstTryPassphrase = true;
		private final IUserAuthenticator fAuthenticator;

		public JSchUserInfo(IUserAuthenticator authenticator) {
			fAuthenticator = authenticator;
		}

		@Override
		public String getPassphrase() {
			if (logging) {
				System.out.println("getPassphrase"); //$NON-NLS-1$
			}
			return JSchConnection.this.getPassphrase();
		}

		@Override
		public String getPassword() {
			if (logging) {
				System.out.println("getPassword"); //$NON-NLS-1$
			}
			return JSchConnection.this.getPassword();
		}

		@Override
		public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt,
				boolean[] echo) {
			if (logging) {
				System.out.println("promptKeyboardInteractive:" + destination + ":" + name + ":" + instruction); //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
				for (String p : prompt) {
					System.out.println(" " + p); //$NON-NLS-1$
				}
			}

			if (fAuthenticator != null) {
				String[] result = fAuthenticator.prompt(destination, name, instruction, prompt, echo);
				if (result != null) {
					if (prompt.length == 1 && prompt[0].trim().equalsIgnoreCase("password:")) { //$NON-NLS-1$
						fAttributes.setSecureAttribute(JSchConnectionAttributes.PASSWORD_ATTR, result[0]);
					}
				}
				return result;
			}
			return null;
		}

		@Override
		public boolean promptPassphrase(String message) {
			if (logging) {
				System.out.println("promptPassphrase:" + message); //$NON-NLS-1$
			}
			if (firstTryPassphrase && !getPassphrase().equals("")) { //$NON-NLS-1$
				firstTryPassphrase = false;
				return true;
			}
			if (fAuthenticator != null) {
				PasswordAuthentication auth = fAuthenticator.prompt(getUsername(), message);
				if (auth == null) {
					return false;
				}
				fAttributes.setAttribute(JSchConnectionAttributes.USERNAME_ATTR, auth.getUserName());
				fAttributes.setSecureAttribute(JSchConnectionAttributes.PASSPHRASE_ATTR, new String(auth.getPassword()));
				return true;
			}
			return false;
		}

		@Override
		public boolean promptPassword(String message) {
			if (logging) {
				System.out.println("promptPassword:" + message); //$NON-NLS-1$
			}
			if (fAuthenticator != null) {
				PasswordAuthentication auth = fAuthenticator.prompt(getUsername(), message);
				if (auth == null) {
					return false;
				}
				fAttributes.setAttribute(JSchConnectionAttributes.USERNAME_ATTR, auth.getUserName());
				fAttributes.setSecureAttribute(JSchConnectionAttributes.PASSWORD_ATTR, new String(auth.getPassword()));
				return true;
			}
			return false;
		}

		@Override
		public boolean promptYesNo(String message) {
			if (logging) {
				System.out.println("promptYesNo:" + message); //$NON-NLS-1$
			}
			if (fAuthenticator != null) {
				int prompt = fAuthenticator.prompt(IUserAuthenticator.QUESTION, Messages.AuthInfo_Authentication_message, message,
						new int[] { IUserAuthenticator.YES, IUserAuthenticator.NO }, IUserAuthenticator.YES);
				return prompt == IUserAuthenticator.YES;
			}
			return true;
		}

		@Override
		public void showMessage(String message) {
			if (logging) {
				System.out.println("showMessage:" + message); //$NON-NLS-1$
			}
			if (fAuthenticator != null) {
				fAuthenticator.prompt(IUserAuthenticator.INFORMATION, Messages.AuthInfo_Authentication_message, message,
						new int[] { IUserAuthenticator.OK }, IUserAuthenticator.OK);
			}
		}
	}

	private final boolean logging = false;

	public static final int DEFAULT_PORT = 22;
	public static final int DEFAULT_TIMEOUT = 5;
	public static final boolean DEFAULT_IS_PASSWORD = true;
	public static final boolean DEFAULT_USE_LOGIN_SHELL = true;
	public static final String EMPTY_STRING = ""; //$NON-NLS-1$

	private String fWorkingDir;

	private final IJSchService fJSchService;

	private final JSchConnectionAttributes fAttributes;
	private final JSchConnectionManager fManager;
	private final Map<String, String> fEnv = new HashMap<String, String>();
	private final Map<String, String> fProperties = new HashMap<String, String>();
	private final IRemoteServices fRemoteServices;
	private final ListenerList fListeners = new ListenerList();
	private final List<Session> fSessions = new ArrayList<Session>();

	private ChannelSftp fSftpChannel;

	public JSchConnection(String name, IRemoteServices services) {
		fRemoteServices = services;
		fManager = (JSchConnectionManager) services.getConnectionManager();
		fAttributes = new JSchConnectionAttributes(name);
		fJSchService = Activator.getDefault().getService();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#addConnectionChangeListener
	 * (org.eclipse.remote.core.IRemoteConnectionChangeListener)
	 */
	@Override
	public void addConnectionChangeListener(IRemoteConnectionChangeListener listener) {
		fListeners.add(listener);
	}

	private boolean checkConfiguration(Session session, IProgressMonitor monitor) throws RemoteConnectionException {
		SubMonitor subMon = SubMonitor.convert(monitor, 10);
		ChannelSftp sftp;
		try {
			/*
			 * First, check if sftp is supported at all. This is required for EFS, so throw exception if not supported.
			 */
			sftp = openSftpChannel(session);
		} catch (RemoteConnectionException e) {
			throw new RemoteConnectionException(Messages.JSchConnection_Remote_host_does_not_support_sftp);
		}
		/*
		 * While sftp channel is open, try opening an exec channel. If it doesn't succeed, then MaxSession is < 2 so we need at
		 * least one additional session.
		 */
		try {
			loadEnv(subMon.newChild(10));
		} catch (RemoteConnectionException e) {
			if (e.getMessage().contains("channel is not opened")) { //$NON-NLS-1$
				return false;
			}
		} finally {
			if (sftp != null) {
				sftp.disconnect();
			}
		}
		return true;
	}

	/**
	 * @throws RemoteConnectionException
	 */
	private void checkIsConfigured() throws RemoteConnectionException {
		if (fAttributes.getAttribute(JSchConnectionAttributes.ADDRESS_ATTR, null) == null) {
			throw new RemoteConnectionException(Messages.JSchConnection_remote_address_must_be_set);
		}
		if (fAttributes.getAttribute(JSchConnectionAttributes.USERNAME_ATTR, null) == null) {
			throw new RemoteConnectionException(Messages.JSchConnection_username_must_be_set);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#close()
	 */
	@Override
	public synchronized void close() {
		if (fSftpChannel != null) {
			if (fSftpChannel.isConnected()) {
				fSftpChannel.disconnect();
			}
			fSftpChannel = null;
		}
		for (Session session : fSessions) {
			if (session.isConnected()) {
				session.disconnect();
			}
		}
		fSessions.clear();
		fireConnectionChangeEvent(IRemoteConnectionChangeEvent.CONNECTION_CLOSED);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	@Override
	public int compareTo(IRemoteConnection o) {
		return getName().compareTo(o.getName());
	}

	/**
	 * Execute the command and return the result as a string.
	 * 
	 * @param cmd
	 *            command to execute
	 * @param monitor
	 *            progress monitor
	 * @return result of command
	 * @throws RemoteConnectionException
	 */
	private String executeCommand(String cmd, IProgressMonitor monitor) throws RemoteConnectionException {
		ExecCommand exec = new ExecCommand(this);
		monitor.subTask(NLS.bind(Messages.JSchConnection_Executing_command, cmd));
		return exec.setCommand(cmd).getResult(monitor).trim();
	}

	/**
	 * Notify all fListeners when this connection's status changes.
	 * 
	 * @param event
	 */
	@Override
	public void fireConnectionChangeEvent(final int type) {
		final IRemoteConnection connection = this;
		IRemoteConnectionChangeEvent event = new IRemoteConnectionChangeEvent() {
			@Override
			public IRemoteConnection getConnection() {
				return connection;
			}

			@Override
			public int getType() {
				return type;
			}
		};
		for (Object listener : fListeners.getListeners()) {
			((IRemoteConnectionChangeListener) listener).connectionChanged(event);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#forwardLocalPort(int, java.lang.String, int)
	 */
	@Override
	public void forwardLocalPort(int localPort, String fwdAddress, int fwdPort) throws RemoteConnectionException {
		if (!isOpen()) {
			throw new RemoteConnectionException(Messages.JSchConnection_connectionNotOpen);
		}
		try {
			fSessions.get(0).setPortForwardingL(localPort, fwdAddress, fwdPort);
		} catch (JSchException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#forwardLocalPort(java.lang .String, int,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public int forwardLocalPort(String fwdAddress, int fwdPort, IProgressMonitor monitor) throws RemoteConnectionException {
		if (!isOpen()) {
			throw new RemoteConnectionException(Messages.JSchConnection_connectionNotOpen);
		}
		SubMonitor progress = SubMonitor.convert(monitor, 10);
		progress.beginTask(Messages.JSchConnection_forwarding, 10);
		/*
		 * Start with a different port number, in case we're doing this all on localhost.
		 */
		int localPort = fwdPort + 1;

		/*
		 * Try to find a free port on the remote machine. This take a while, so allow it to be canceled. If we've tried all
		 * ports (which could take a very long while) then bail out.
		 */
		while (!progress.isCanceled()) {
			try {
				forwardLocalPort(localPort, fwdAddress, fwdPort);
			} catch (AddressInUseException e) {
				if (++localPort == fwdPort) {
					throw new UnableToForwardPortException(Messages.JSchConnection_remotePort);
				}
				progress.worked(1);
			}
			return localPort;
		}
		return -1;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#forwardRemotePort(int, java.lang.String, int)
	 */
	@Override
	public void forwardRemotePort(int remotePort, String fwdAddress, int fwdPort) throws RemoteConnectionException {
		if (!isOpen()) {
			throw new RemoteConnectionException(Messages.JSchConnection_connectionNotOpen);
		}
		try {
			fSessions.get(0).setPortForwardingR(remotePort, fwdAddress, fwdPort);
		} catch (JSchException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#forwardRemotePort(java. lang.String, int,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public int forwardRemotePort(String fwdAddress, int fwdPort, IProgressMonitor monitor) throws RemoteConnectionException {
		if (!isOpen()) {
			throw new RemoteConnectionException(Messages.JSchConnection_connectionNotOpen);
		}
		SubMonitor progress = SubMonitor.convert(monitor, 10);
		progress.beginTask(Messages.JSchConnection_forwarding, 10);
		/*
		 * Start with a different port number, in case we're doing this all on localhost.
		 */
		int remotePort = fwdPort + 1;
		/*
		 * Try to find a free port on the remote machine. This take a while, so allow it to be canceled. If we've tried all
		 * ports (which could take a very long while) then bail out.
		 */
		while (!progress.isCanceled()) {
			try {
				forwardRemotePort(remotePort, fwdAddress, fwdPort);
				return remotePort;
			} catch (AddressInUseException e) {
				if (++remotePort == fwdPort) {
					throw new UnableToForwardPortException(Messages.JSchConnection_remotePort);
				}
				progress.worked(1);
			}
		}
		return -1;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getAddress()
	 */
	@Override
	public String getAddress() {
		return fAttributes.getAttribute(JSchConnectionAttributes.ADDRESS_ATTR, EMPTY_STRING);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getAttributes()
	 */
	@Override
	public Map<String, String> getAttributes() {
		return Collections.unmodifiableMap(fAttributes.getAttributes());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteServices#getCommandShell(int)
	 */
	@Override
	public IRemoteProcess getCommandShell(int flags) throws IOException {
		throw new IOException("Not currently implemented"); //$NON-NLS-1$
	}

	/**
	 * Get the result of executing a pwd command.
	 * 
	 * @return current working directory
	 */
	private String getCwd(IProgressMonitor monitor) {
		SubMonitor subMon = SubMonitor.convert(monitor, 10);
		try {
			return executeCommand("pwd", subMon.newChild(10)); //$NON-NLS-1$
		} catch (RemoteConnectionException e) {
			// Ignore
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getEnv()
	 */
	@Override
	public Map<String, String> getEnv() {
		return Collections.unmodifiableMap(fEnv);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getEnv(java.lang.String)
	 */
	@Override
	public String getEnv(String name) {
		return getEnv().get(name);
	}

	/**
	 * Open an exec channel to the remote host.
	 * 
	 * @return exec channel or null if the progress monitor was cancelled
	 * 
	 * @throws RemoteConnectionException
	 *             if a channel could not be opened
	 */
	public ChannelExec getExecChannel() throws RemoteConnectionException {
		try {
			return (ChannelExec) fSessions.get(0).openChannel("exec"); //$NON-NLS-1$
		} catch (JSchException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteServices#getFileManager()
	 */
	@Override
	public IRemoteFileManager getFileManager() {
		return new JSchFileManager(this);
	}

	public JSchConnectionAttributes getInfo() {
		return fAttributes;
	}

	public String getKeyFile() {
		return fAttributes.getAttribute(JSchConnectionAttributes.KEYFILE_ATTR, EMPTY_STRING);
	}

	public JSchConnectionManager getManager() {
		return fManager;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getName()
	 */
	@Override
	public String getName() {
		return fAttributes.getName();
	}

	public String getPassphrase() {
		return fAttributes.getSecureAttribute(JSchConnectionAttributes.PASSPHRASE_ATTR, EMPTY_STRING);
	}

	public String getPassword() {
		return fAttributes.getSecureAttribute(JSchConnectionAttributes.PASSWORD_ATTR, EMPTY_STRING);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getPort()
	 */
	@Override
	public int getPort() {
		return fAttributes.getInt(JSchConnectionAttributes.PORT_ATTR, DEFAULT_PORT);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteServices#getProcessBuilder(java.util.List)
	 */
	@Override
	public IRemoteProcessBuilder getProcessBuilder(List<String> command) {
		return new JSchProcessBuilder(this, command);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteServices#getProcessBuilder(java.lang.String[])
	 */
	@Override
	public IRemoteProcessBuilder getProcessBuilder(String... command) {
		return new JSchProcessBuilder(this, command);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getProperty(java.lang.String )
	 */
	@Override
	public String getProperty(String key) {
		return fProperties.get(key);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getRemoteServices()
	 */
	@Override
	public IRemoteServices getRemoteServices() {
		return fRemoteServices;
	}

	/**
	 * Open an sftp channel to the remote host. Always use the second session if available.
	 * 
	 * @return sftp channel or null if the progress monitor was cancelled
	 * @throws RemoteConnectionException
	 *             if a channel could not be opened
	 */
	public ChannelSftp getSftpChannel() throws RemoteConnectionException {
		if (fSftpChannel == null || fSftpChannel.isClosed()) {
			Session session = fSessions.get(0);
			if (fSessions.size() > 1) {
				session = fSessions.get(1);
			}
			fSftpChannel = openSftpChannel(session);
			if (fSftpChannel == null) {
				throw new RemoteConnectionException(Messages.JSchConnection_Unable_to_open_sftp_channel);
			}
		}
		return fSftpChannel;
	}

	public int getTimeout() {
		return fAttributes.getInt(JSchConnectionAttributes.TIMEOUT_ATTR, DEFAULT_TIMEOUT);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getUsername()
	 */
	@Override
	public String getUsername() {
		return fAttributes.getAttribute(JSchConnectionAttributes.USERNAME_ATTR, EMPTY_STRING);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getWorkingCopy()
	 */
	@Override
	public IRemoteConnectionWorkingCopy getWorkingCopy() {
		return new JSchConnectionWorkingCopy(this);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#getWorkingDirectory()
	 */
	@Override
	public String getWorkingDirectory() {
		if (!isOpen()) {
			return "/"; //$NON-NLS-1$
		}
		if (fWorkingDir == null) {
			return "/"; //$NON-NLS-1$
		}
		return fWorkingDir;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#isOpen()
	 */
	@Override
	public boolean isOpen() {
		boolean isOpen = fSessions.size() > 0;
		if (isOpen) {
			for (Session session : fSessions) {
				isOpen &= session.isConnected();
			}
		}
		if (!isOpen) {
			close(); // Cleanup if session is closed
		}
		return isOpen;
	}

	public boolean isPasswordAuth() {
		return fAttributes.getBoolean(JSchConnectionAttributes.IS_PASSWORD_ATTR, DEFAULT_IS_PASSWORD);
	}

	private void loadEnv(IProgressMonitor monitor) throws RemoteConnectionException {
		SubMonitor subMon = SubMonitor.convert(monitor, 10);
		String env = executeCommand("printenv", subMon.newChild(10)); //$NON-NLS-1$
		String[] vars = env.split("\n"); //$NON-NLS-1$
		for (String var : vars) {
			String[] kv = var.split("="); //$NON-NLS-1$
			if (kv.length == 2) {
				fEnv.put(kv[0], kv[1]);
			}
		}
	}

	/**
	 * 
	 * Load the following hard-coded properties at runtime:
	 * 
	 * <dl>
	 * <dt>file.separator
	 * <dd>File separator character of the (remote) connection. Hardcoded "/" (forward slash).
	 * <dt>path.separator
	 * <dd>Path separator character of the (remote) connection. Hardcoded ":" (colon).
	 * <dt>line.separator
	 * <dd>Line separator character of the (remote) connection. Hardcoded "\n" (new-line).
	 * <dt>user.home
	 * <dd>User home directory on the (remote) connection.
	 * <dt>os.name
	 * <dd>Operating system name of the (remote) connection. For example, given results from the "uname" command:
	 * <ul>
	 * <li>Linux</li>
	 * <li>AIX</li>
	 * <li>Mac OS X - if results equal "Darwin" then results from "sw_vers -productName"</li>
	 * <li>everything else - results from "uname" command</li>
	 * </ul>
	 * <dt>os.version
	 * <dd>Operating system version of the (remote) connection. For example:
	 * <ul>
	 * <li>For Linux - results from "uname -r" such as "2.6.32-279.2.1.el6.x86_64"</li>
	 * <li>For AIX - results from "oslevel" such as "7.1.0.0"</li>
	 * <li>For Mac OS X - results from "sw_vers -productVersion" such as "10.8.3"</li>
	 * <li>For everything else - "unknown"</li>
	 * </ul>
	 * <dt>os.arch
	 * <dd>Machine architecture of the (remote) connection. For example:
	 * <ul>
	 * <li>For Linux - results from "uname -m" such as "x86_64"</li>
	 * <li>For AIX - if results from "uname -p" equals "powerpc"
	 * <ul style="list-style: none;">
	 * <li>then if "prtconf -k" contains "64-bit" then "ppc64" else "ppc"</li>
	 * <li>else the result from "uname -p"</li>
	 * </ul>
	 * </li>
	 * <li>For Mac OS X - if results from "uname -m" equals "i386"
	 * <ul style="list-style: none;">
	 * <li>then if results from "sysctl -n hw.optional.x86_64" equals "1" then "x86_64" else the results from "uname -m"</li>
	 * <li>else the results from "uname -m"</li>
	 * </ul>
	 * </li>
	 * <li>For everything else - "unknown"</li>
	 * </ul>
	 * <dl>
	 * 
	 */
	private void loadProperties(IProgressMonitor monitor) throws RemoteConnectionException {
		SubMonitor subMon = SubMonitor.convert(monitor, 100);
		fProperties.put(FILE_SEPARATOR_PROPERTY, "/"); //$NON-NLS-1$
		fProperties.put(PATH_SEPARATOR_PROPERTY, ":"); //$NON-NLS-1$
		fProperties.put(LINE_SEPARATOR_PROPERTY, "\n"); //$NON-NLS-1$
		fProperties.put(USER_HOME_PROPERTY, getWorkingDirectory());

		String osVersion;
		String osArch;
		String osName = executeCommand("uname", subMon.newChild(10)); //$NON-NLS-1$
		if (osName.equalsIgnoreCase("Linux")) { //$NON-NLS-1$
			osArch = executeCommand("uname -m", subMon.newChild(10)); //$NON-NLS-1$
			osVersion = executeCommand("uname -r", subMon.newChild(10)); //$NON-NLS-1$
		} else if (osName.equalsIgnoreCase("Darwin")) { //$NON-NLS-1$
			osName = executeCommand("sw_vers -productName", subMon.newChild(10)); //$NON-NLS-1$
			osVersion = executeCommand("sw_vers -productVersion", subMon.newChild(10)); //$NON-NLS-1$
			osArch = executeCommand("uname -m", subMon.newChild(10)); //$NON-NLS-1$
			if (osArch.equalsIgnoreCase("i386")) { //$NON-NLS-1$
				String opt = executeCommand("sysctl -n hw.optional.x86_64", subMon.newChild(10)); //$NON-NLS-1$
				if (opt.equals("1")) { //$NON-NLS-1$
					osArch = "x86_64"; //$NON-NLS-1$
				}
			}
		} else if (osName.equalsIgnoreCase("AIX")) { //$NON-NLS-1$
			osArch = executeCommand("uname -p", subMon.newChild(10)); //$NON-NLS-1$
			osVersion = executeCommand("oslevel", subMon.newChild(10)); //$NON-NLS-1$
			if (osArch.equalsIgnoreCase("powerpc")) { //$NON-NLS-1$
				/* Make the architecture match what Linux produces: either ppc or ppc64 */
				osArch = "ppc"; //$NON-NLS-1$
				/* Get Kernel type either 32-bit or 64-bit */
				String opt = executeCommand("prtconf -k", subMon.newChild(10)); //$NON-NLS-1$
				if (opt.indexOf("64-bit") > 0) { //$NON-NLS-1$
					osArch += "64"; //$NON-NLS-1$
				}
			}
		} else {
			osVersion = "unknown"; //$NON-NLS-1$
			osArch = "unknown"; //$NON-NLS-1$
		}
		fProperties.put(OS_NAME_PROPERTY, osName);
		fProperties.put(OS_VERSION_PROPERTY, osVersion);
		fProperties.put(OS_ARCH_PROPERTY, osArch);
	}

	private Session newSession(final IUserAuthenticator authenticator, IProgressMonitor monitor) throws RemoteConnectionException {
		SubMonitor progress = SubMonitor.convert(monitor, 10);
		try {
			if (!isPasswordAuth()) {
				fJSchService.getJSch().addIdentity(getKeyFile());
			}
			Session session = fJSchService.createSession(getAddress(), getPort(), getUsername());
			session.setUserInfo(new JSchUserInfo(authenticator));
			if (isPasswordAuth()) {
				session.setConfig("PreferredAuthentications","password,keyboard-interactive"); //$NON-NLS-1$ //$NON-NLS-2$
				session.setPassword(getPassword());
			}
			fJSchService.connect(session, getTimeout() * 1000, progress.newChild(10));
			if (!progress.isCanceled()) {
				fSessions.add(session);
				return session;
			}
			return null;
		} catch (JSchException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#open()
	 */
	@Override
	public void open(IProgressMonitor monitor) throws RemoteConnectionException {
		if (!isOpen()) {
			checkIsConfigured();
			SubMonitor subMon = SubMonitor.convert(monitor, 60);
			Session session = newSession(fManager.getUserAuthenticator(this), subMon.newChild(10));
			if (subMon.isCanceled()) {
				throw new RemoteConnectionException(Messages.JSchConnection_Connection_was_cancelled);
			}
			//getCwd checks the exec channel before checkConfiguration checks the sftp channel
			fWorkingDir = getCwd(subMon.newChild(10)); 
			if (!checkConfiguration(session, subMon.newChild(20))) {
				newSession(fManager.getUserAuthenticator(this), subMon.newChild(10));
				loadEnv(subMon.newChild(10));
			}
			loadProperties(subMon.newChild(10));
			fireConnectionChangeEvent(IRemoteConnectionChangeEvent.CONNECTION_OPENED);
		}
	}

	private ChannelSftp openSftpChannel(Session session) throws RemoteConnectionException {
		try {
			ChannelSftp channel = (ChannelSftp) session.openChannel("sftp"); //$NON-NLS-1$
			channel.connect();
			return channel;
		} catch (JSchException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#removeConnectionChangeListener
	 * (org.eclipse.remote.core.IRemoteConnectionChangeListener)
	 */
	@Override
	public void removeConnectionChangeListener(IRemoteConnectionChangeListener listener) {
		fListeners.remove(listener);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#removeLocalPortForwarding(int)
	 */
	@Override
	public void removeLocalPortForwarding(int port) throws RemoteConnectionException {
		if (!isOpen()) {
			throw new RemoteConnectionException(Messages.JSchConnection_connectionNotOpen);
		}
		try {
			fSessions.get(0).delPortForwardingL(port);
		} catch (JSchException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#removeRemotePortForwarding(int)
	 */
	@Override
	public void removeRemotePortForwarding(int port) throws RemoteConnectionException {
		if (!isOpen()) {
			throw new RemoteConnectionException(Messages.JSchConnection_connectionNotOpen);
		}
		try {
			fSessions.get(0).delPortForwardingR(port);
		} catch (JSchException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#setWorkingDirectory(java.lang.String)
	 */
	@Override
	public void setWorkingDirectory(String path) {
		if (new Path(path).isAbsolute()) {
			fWorkingDir = path;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.IRemoteConnection#supportsTCPPortForwarding()
	 */
	@Override
	public boolean supportsTCPPortForwarding() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		String str = getName() + " [" + getUsername() + "@" + getAddress(); //$NON-NLS-1$ //$NON-NLS-2$
		if (getPort() >= 0) {
			str += ":" + getPort(); //$NON-NLS-1$
		}
		return str + "]"; //$NON-NLS-1$
	}

	public boolean useLoginShell() {
		return fAttributes.getBoolean(JSchConnectionAttributes.USE_LOGIN_SHELL_ATTR, DEFAULT_USE_LOGIN_SHELL);
	}
}
