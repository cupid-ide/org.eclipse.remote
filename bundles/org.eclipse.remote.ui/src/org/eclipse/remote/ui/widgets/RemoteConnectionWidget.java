/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Greg Watson - Initial API and implementation
 *******************************************************************************/
package org.eclipse.remote.ui.widgets;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.ListenerList;
import org.eclipse.jface.operation.IRunnableContext;
import org.eclipse.remote.core.IRemoteConnection;
import org.eclipse.remote.core.IRemoteConnectionManager;
import org.eclipse.remote.core.IRemoteConnectionWorkingCopy;
import org.eclipse.remote.core.IRemotePreferenceConstants;
import org.eclipse.remote.core.IRemoteServices;
import org.eclipse.remote.core.RemoteServices;
import org.eclipse.remote.internal.core.RemoteServicesDescriptor;
import org.eclipse.remote.internal.core.RemoteServicesImpl;
import org.eclipse.remote.internal.core.preferences.Preferences;
import org.eclipse.remote.internal.ui.messages.Messages;
import org.eclipse.remote.ui.IRemoteUIConnectionManager;
import org.eclipse.remote.ui.IRemoteUIConnectionWizard;
import org.eclipse.remote.ui.RemoteUIServices;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

/**
 * Widget to allow the user to select a service provider and connection. Provides a "New" button to create a new connection.
 * 
 * If title is supplied then the widget will be placed in a group.
 * 
 * @since 5.0
 * 
 */
public class RemoteConnectionWidget extends Composite {
	/**
	 * Listener for widget selected events. Allows the events to be enabled/disabled.
	 * 
	 */
	protected class WidgetListener implements SelectionListener {
		/** State of the listener (enabled/disabled). */
		private boolean listenerEnabled = true;

		/**
		 * Disable listener, received events shall be ignored.
		 */
		public void disable() {
			setEnabled(false);
		}

		protected void doWidgetDefaultSelected(SelectionEvent e) {
			// Default empty implementation.
		}

		/**
		 * Enable the listener to handle events.
		 */
		public void enable() {
			setEnabled(true);
		}

		/**
		 * Test if the listener is enabled.
		 */
		public synchronized boolean isEnabled() {
			return listenerEnabled;
		}

		/**
		 * Set listener enabled state
		 * 
		 * @param enabled
		 */
		public synchronized void setEnabled(boolean enabled) {
			listenerEnabled = enabled;
		}

		@Override
		public void widgetDefaultSelected(SelectionEvent e) {
			if (isEnabled()) {
				widgetSelected(e);
			}
		}

		@Override
		public void widgetSelected(SelectionEvent e) {
			if (isEnabled()) {
				Object source = e.getSource();
				if (source == fServicesCombo) {
					handleRemoteServiceSelected(null);
				} else if (source == fConnectionCombo) {
					handleConnectionSelected();
				} else if (source == fNewConnectionButton) {
					handleNewRemoteConnectionSelected();
				} else if (source == fLocalButton) {
					handleButtonSelected();
				}
			}
		}

	}

	public static final String DEFAULT_CONNECTION_NAME = "Remote Host"; //$NON-NLS-1$

	/**
	 * Force the use of a remote provider dialog, regardless of the PRE_REMOTE_SERVICES_ID preference setting.
	 * 
	 * @since 7.0
	 */
	public static int FLAG_FORCE_PROVIDER_SELECTION = 1 << 0;

	/**
	 * Do not provide a selection for local services.
	 * 
	 * @since 7.0
	 */
	public static int FLAG_NO_LOCAL_SELECTION = 1 << 1;

	private static final String EMPTY_STRING = ""; //$NON-NLS-1$

	private Combo fServicesCombo = null;
	private Button fLocalButton;
	private Button fRemoteButton;
	private final Combo fConnectionCombo;
	private final Button fNewConnectionButton;

	private final List<RemoteServicesDescriptor> fRemoteServices;
	private IRemoteConnection fSelectedConnection;
	private IRemoteServices fDefaultServices;
	private boolean fSelectionListernersEnabled = true;
	private boolean fEnabled = true;

	private final IRunnableContext fContext;

	private final ListenerList fSelectionListeners = new ListenerList();
	private final WidgetListener fWidgetListener = new WidgetListener();

	/**
	 * Constructor
	 * 
	 * @param parent
	 *            parent composite
	 * @param style
	 *            style or SWT.NONE
	 * @param title
	 *            if a title is supplied then the widget will be placed in a group. Can be null.
	 * @param flags
	 *            a combination of flags that modify the behavior of the widget.
	 */
	public RemoteConnectionWidget(Composite parent, int style, String title, int flags) {
		this(parent, style, title, flags, null);
	}

	/**
	 * Constructor
	 * 
	 * @param parent
	 *            parent composite
	 * @param style
	 *            style or SWT.NONE
	 * @param title
	 *            if a title is supplied then the widget will be placed in a group. Can be null.
	 * @param flags
	 *            a combination of flags that modify the behavior of the widget.
	 * @param context
	 *            runnable context, or null
	 */
	public RemoteConnectionWidget(Composite parent, int style, String title, int flags, IRunnableContext context) {
		super(parent, style);
		fContext = context;

		Composite body = this;

		GridLayout layout = new GridLayout(1, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		layout.numColumns = 4;
		setLayout(layout);
		setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		if (title != null) {
			Group group = new Group(this, SWT.NONE);
			group.setText(title);
			GridLayout groupLayout = new GridLayout(1, false);
			groupLayout.marginHeight = 0;
			groupLayout.marginWidth = 0;
			groupLayout.numColumns = 4;
			group.setLayout(groupLayout);
			group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
			layout.numColumns = 1;
			body = group;
		}

		/*
		 * Check if we need a remote services combo, or we should just use the default provider
		 */
		if ((flags & FLAG_FORCE_PROVIDER_SELECTION) == 0) {
			String id = Preferences.getString(IRemotePreferenceConstants.PREF_REMOTE_SERVICES_ID);
			if (id != null) {
				fDefaultServices = getRemoteServices(id);
			}
		}

		if (fDefaultServices == null) {
			/*
			 * Remote provider
			 */
			Label label = new Label(body, SWT.NONE);
			label.setText(Messages.RemoteConnectionWidget_remoteServiceProvider);
			label.setLayoutData(new GridData());

			fServicesCombo = new Combo(body, SWT.DROP_DOWN | SWT.READ_ONLY);
			GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
			gd.horizontalSpan = 3;
			fServicesCombo.setLayoutData(gd);
			fServicesCombo.addSelectionListener(fWidgetListener);
			fServicesCombo.setFocus();
		}

		if ((flags & FLAG_NO_LOCAL_SELECTION) == 0 && (flags & FLAG_FORCE_PROVIDER_SELECTION) == 0) {
			fLocalButton = new Button(body, SWT.RADIO);
			fLocalButton.setText(Messages.RemoteConnectionWidget_Local);
			fLocalButton.setLayoutData(new GridData());
			fLocalButton.addSelectionListener(fWidgetListener);
			fLocalButton.setSelection(false);

			fRemoteButton = new Button(body, SWT.RADIO);
			fRemoteButton.setText(Messages.RemoteConnectionWidget_Remote);
			fRemoteButton.setLayoutData(new GridData());
		} else {
			Label remoteLabel = new Label(body, SWT.NONE);
			remoteLabel.setText(Messages.RemoteConnectionWidget_connectionName);
			remoteLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
		}

		fConnectionCombo = new Combo(body, SWT.DROP_DOWN | SWT.READ_ONLY);
		fConnectionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		fConnectionCombo.addSelectionListener(fWidgetListener);
		if (fDefaultServices != null) {
			fConnectionCombo.setFocus();
		}
		fConnectionCombo.setEnabled(false);

		fNewConnectionButton = new Button(body, SWT.PUSH);
		fNewConnectionButton.setText(Messages.RemoteConnectionWidget_new);
		fNewConnectionButton.setLayoutData(new GridData());
		fNewConnectionButton.addSelectionListener(fWidgetListener);

		fRemoteServices = RemoteServicesImpl.getRemoteServiceDescriptors();

		if (fServicesCombo != null) {
			initializeRemoteServicesCombo(null);
		}

		handleRemoteServiceSelected(null);

		if (fLocalButton != null) {
			handleButtonSelected();
		}
	}

	/**
	 * Adds the listener to the collection of listeners who will be notified when the user changes the receiver's selection, by
	 * sending it one of the messages defined in the <code>SelectionListener</code> interface.
	 * <p>
	 * <code>widgetSelected</code> is called when the user changes the service provider or connection.
	 * </p>
	 * 
	 * @param listener
	 *            the listener which should be notified
	 */
	public void addSelectionListener(SelectionListener listener) {
		fSelectionListeners.add(listener);
	}

	/**
	 * Get the new button from the widget
	 * 
	 * @return button
	 * @since 7.0
	 */
	public Button getButton() {
		return fNewConnectionButton;
	}

	/**
	 * Get the connection that is currently selected in the widget, or null if there is no selected connection.
	 * 
	 * @return selected connection
	 */
	public IRemoteConnection getConnection() {
		return fSelectedConnection;
	}

	private IRemoteConnection getRemoteConnection(IRemoteServices services, String name) {
		IRemoteConnectionManager manager = getRemoteConnectionManager(services);
		if (manager != null) {
			return manager.getConnection(name);
		}
		return null;
	}

	private IRemoteConnection getRemoteConnection(String name) {
		IRemoteServices services = getSelectedServices();
		if (fDefaultServices != null && name.equals(IRemoteConnectionManager.LOCAL_CONNECTION_NAME)) {
			services = RemoteServices.getLocalServices();
		}
		return getRemoteConnection(services, name);
	}

	protected IRemoteConnectionManager getRemoteConnectionManager(IRemoteServices services) {
		if (services != null) {
			return services.getConnectionManager();
		}
		return null;
	}

	protected IRemoteServices getRemoteServices(String id) {
		if (id != null && !id.equals(EMPTY_STRING)) {
			return RemoteUIServices.getRemoteServices(id, fContext);
		}
		return null;
	}

	private IRemoteServices getSelectedServices() {
		if (fDefaultServices != null) {
			return fDefaultServices;
		}
		int selectionIndex = fServicesCombo.getSelectionIndex();
		if (fRemoteServices.size() > 0 && selectionIndex > 0) {
			return RemoteServices.getRemoteServices(fRemoteServices.get(selectionIndex - 1).getId());
		}
		return null;
	}

	private IRemoteUIConnectionManager getUIConnectionManager() {
		IRemoteServices services = getSelectedServices();
		if (services != null) {
			return RemoteUIServices.getRemoteUIServices(services).getUIConnectionManager();
		}
		return null;
	}

	private void handleButtonSelected() {
		fRemoteButton.setSelection(!fLocalButton.getSelection());
		updateEnablement();
		handleConnectionSelected();
	}

	/**
	 * Handle the section of a new connection. Update connection option buttons appropriately.
	 */
	protected void handleConnectionSelected() {
		final boolean enabled = fWidgetListener.isEnabled();
		fWidgetListener.disable();
		IRemoteConnection selectedConnection = null;
		if (fLocalButton != null && fLocalButton.getSelection()) {
			selectedConnection = RemoteServices.getLocalServices().getConnectionManager()
					.getConnection(IRemoteConnectionManager.LOCAL_CONNECTION_NAME);
		} else {
			int currentSelection = fConnectionCombo.getSelectionIndex();
			if (currentSelection > 0) {
				String connectionName = fConnectionCombo.getItem(currentSelection);
				selectedConnection = getRemoteConnection(connectionName);
			}
		}
		if (selectedConnection == null || fSelectedConnection == null
				|| !selectedConnection.getName().equals(fSelectedConnection.getName())) {
			fSelectedConnection = selectedConnection;
			Event evt = new Event();
			evt.widget = this;
			notifyListeners(new SelectionEvent(evt));
		}
		fWidgetListener.setEnabled(enabled);
	}

	/**
	 * Handle creation of a new connection by pressing the 'New...' button. Calls handleRemoteServicesSelected() to update the
	 * connection combo with the new connection.
	 * 
	 * TODO should probably select the new connection
	 */
	protected void handleNewRemoteConnectionSelected() {
		if (getUIConnectionManager() != null) {
			IRemoteUIConnectionWizard wizard = getUIConnectionManager().getConnectionWizard(getShell());
			if (wizard != null) {
				wizard.setConnectionName(initialConnectionName());
				IRemoteConnectionWorkingCopy conn = wizard.open();
				if (conn != null) {
					handleRemoteServiceSelected(conn.save());
					handleConnectionSelected();
				}
			}
		}
	}

	/**
	 * Handle selection of a new remote services provider from the remote services combo. Handles the special case where the
	 * services combo is null and a local connection is supplied. In this case, the selected services are not changed.
	 * 
	 * The assumption is that this will trigger a call to the selection handler for the connection combo.
	 * 
	 * @param conn
	 *            connection to select as current. If conn is null, select the first item in the list.
	 * @param notify
	 *            if true, notify handlers that the connection has changed. This should only happen if the user changes the
	 *            connection.
	 */
	protected void handleRemoteServiceSelected(IRemoteConnection conn) {
		final boolean enabled = fWidgetListener.isEnabled();
		fWidgetListener.disable();
		try {
			IRemoteServices selectedServices = getSelectedServices();
			if (conn != null) {
				selectedServices = conn.getRemoteServices();
			}

			/*
			 * If a connection was supplied, set its remote service provider in the combo. Otherwise use the currently selected
			 * service.
			 */
			if (fDefaultServices == null && conn != null) {
				for (int index = 0; index < fRemoteServices.size(); index++) {
					if (fRemoteServices.get(index).getId().equals(selectedServices.getId())) {
						fServicesCombo.select(index + 1);
						break;
					}
				}
			}

			fConnectionCombo.removeAll();
			fConnectionCombo.add(Messages.RemoteConnectionWidget_selectConnection);

			if (selectedServices == null) {
				fConnectionCombo.select(0);
				fConnectionCombo.setEnabled(false);
				fNewConnectionButton.setEnabled(false);
			} else {
				fConnectionCombo.setEnabled(true);

				IRemoteConnectionManager connectionManager = selectedServices.getConnectionManager();

				/*
				 * Populate the connection combo and select the connection
				 */
				int selected = 0;
				int offset = 1;

				Set<IRemoteConnection> sorted = new TreeSet<IRemoteConnection>(connectionManager.getConnections());

				for (IRemoteConnection s : sorted) {
					fConnectionCombo.add(s.getName());
					if (conn != null && s.getName().equals(conn.getName())) {
						selected = offset;
					}
					offset++;
				}

				fConnectionCombo.select(selected);
				handleConnectionSelected();

				/*
				 * Enable 'new' button if new connections are supported
				 */
				fNewConnectionButton
						.setEnabled((selectedServices.getCapabilities() & IRemoteServices.CAPABILITY_ADD_CONNECTIONS) != 0);
			}
		} finally {
			fWidgetListener.setEnabled(enabled);
		}
	}

	private String initialConnectionName() {
		String name = DEFAULT_CONNECTION_NAME;
		int count = 1;
		while (getSelectedServices().getConnectionManager().getConnection(name) != null) {
			name = DEFAULT_CONNECTION_NAME + " " + count++; //$NON-NLS-1$
		}
		return name;
	}

	/**
	 * Initialize the contents of the remote services combo. Keeps an array of remote services that matches the combo elements.
	 * Returns the id of the selected element.
	 * 
	 * @since 6.0
	 */
	protected void initializeRemoteServicesCombo(String id) {
		final boolean enabled = fWidgetListener.isEnabled();
		fWidgetListener.disable();
		IRemoteServices defService = null;
		if (id != null) {
			defService = getRemoteServices(id);
		}
		fServicesCombo.removeAll();
		int offset = 1;
		int defIndex = 0;
		fServicesCombo.add(Messages.RemoteConnectionWidget_selectRemoteProvider);
		for (int i = 0; i < fRemoteServices.size(); i++) {
			fServicesCombo.add(fRemoteServices.get(i).getName());
			if (defService != null && fRemoteServices.get(i).equals(defService)) {
				defIndex = i + offset;
			}
		}
		if (fRemoteServices.size() > 0) {
			fServicesCombo.select(defIndex);
		}
		fWidgetListener.setEnabled(enabled);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.swt.widgets.Control#isEnabled()
	 */
	@Override
	public boolean isEnabled() {
		return fEnabled;
	}

	private void notifyListeners(SelectionEvent e) {
		if (fSelectionListernersEnabled) {
			for (Object listener : fSelectionListeners.getListeners()) {
				((SelectionListener) listener).widgetSelected(e);
			}
		}
	}

	/**
	 * Remove a listener that will be notified when one of the widget's controls are selected
	 * 
	 * @param listener
	 *            listener to remove
	 */
	public void removeSelectionListener(SelectionListener listener) {
		fSelectionListeners.remove(listener);
	}

	/**
	 * Set the connection that should be selected in the widget.
	 * 
	 * @param connection
	 *            connection to select
	 */
	public void setConnection(IRemoteConnection connection) {
		fSelectionListernersEnabled = false;
		if (fLocalButton != null && connection != null && connection.getRemoteServices() == RemoteServices.getLocalServices()) {
			fLocalButton.setSelection(true);
			handleButtonSelected();
		} else {
			handleRemoteServiceSelected(connection);
		}
		handleConnectionSelected();
		updateEnablement();
		fSelectionListernersEnabled = true;
	}

	/**
	 * Set the connection that should be selected in the widget.
	 * 
	 * @param id
	 *            remote services id
	 * @param name
	 *            connection name
	 * @since 6.0
	 */
	public void setConnection(String id, String name) {
		IRemoteServices services = getRemoteServices(id);
		if (services != null) {
			IRemoteConnection connection = getRemoteConnection(services, name);
			if (connection != null) {
				setConnection(connection);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.swt.widgets.Control#setEnabled(boolean)
	 */
	@Override
	public void setEnabled(boolean enabled) {
		fEnabled = enabled;
		updateEnablement();
	}

	private void updateEnablement() {
		if (fDefaultServices != null) {
			boolean isRemote = true;
			if (fLocalButton != null) {
				fLocalButton.setEnabled(fEnabled);
				fRemoteButton.setEnabled(fEnabled);
				isRemote = !fLocalButton.getSelection();
			}
			fConnectionCombo.setEnabled(fEnabled && isRemote);
			fNewConnectionButton.setEnabled(fEnabled && isRemote
					&& (fDefaultServices.getCapabilities() & IRemoteServices.CAPABILITY_ADD_CONNECTIONS) != 0);
		} else {
			IRemoteServices services = getSelectedServices();
			fConnectionCombo.setEnabled(fEnabled && services != null);
			fNewConnectionButton.setEnabled(fEnabled && services != null
					&& (services.getCapabilities() & IRemoteServices.CAPABILITY_ADD_CONNECTIONS) != 0);
			fServicesCombo.setEnabled(fEnabled);
		}
	}
}
