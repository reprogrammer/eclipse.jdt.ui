/*******************************************************************************
 * Copyright (c) 2009, 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.callhierarchy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.eclipse.swt.widgets.TreeItem;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;

import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.eclipse.jdt.core.IMember;

import org.eclipse.jdt.internal.corext.callhierarchy.MethodWrapper;

import org.eclipse.jdt.internal.ui.IJavaHelpContextIds;
import org.eclipse.jdt.internal.ui.JavaPlugin;


/**
 * This action removes a single node from the Call Hierarchy view.
 * 
 * @since 3.6
 */
class RemoveFromViewAction extends Action{


	/**
	 * The Call Hierarchy view part.
	 */
	private CallHierarchyViewPart fPart;

	/**
	 * The Call Hierarchy viewer.
	 */
	private CallHierarchyViewer fCallHierarchyViewer;

	/**
	 * Creates the hide single node action.
	 *
	 * @param part the call hierarchy view part
	 * @param viewer the call hierarchy viewer
	 */
	public RemoveFromViewAction(CallHierarchyViewPart part, CallHierarchyViewer viewer) {
		fPart= part;
		fCallHierarchyViewer= viewer;
		setText(CallHierarchyMessages.RemoveFromViewAction_removeFromView_text);
		setDescription(CallHierarchyMessages.RemoveFromViewAction_removeFromView_description);
		setToolTipText(CallHierarchyMessages.RemoveFromViewAction_removeFromView_tooltip);
		PlatformUI.getWorkbench().getHelpSystem().setHelp(this, IJavaHelpContextIds.CALL_HIERARCHY_REMOVE_FROM_VIEW_ACTION);

		ISharedImages workbenchImages= JavaPlugin.getDefault().getWorkbench().getSharedImages();
		setDisabledImageDescriptor(workbenchImages.getImageDescriptor(ISharedImages.IMG_ELCL_REMOVE_DISABLED));
		setImageDescriptor(workbenchImages.getImageDescriptor(ISharedImages.IMG_ELCL_REMOVE));
		setHoverImageDescriptor(workbenchImages.getImageDescriptor(ISharedImages.IMG_ELCL_REMOVE));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.action.Action#run()
	 */
	@Override
	public void run() {
		IMember[] inputElements= fPart.getInputElements();
		List<IMember> inputList= new ArrayList<IMember>(Arrays.asList(inputElements));
		IMember[] selection= getSelectedElements();
		for (int i= 0; i < selection.length; i++) {
			if (inputList.contains(selection[i]))
				inputList.remove(selection[i]);
		}
		if (inputList.size() > 0) {
			fPart.updateInputHistoryAndDescription(inputElements, inputList.toArray(new IMember[inputList.size()]));
		}
		TreeItem[] items= fCallHierarchyViewer.getTree().getSelection();
		for (int i= 0; i < items.length; i++)
			items[i].dispose();
	}

	/**
	 * Gets the elements selected in the call hierarchy view part.
	 * 
	 * @return the elements
	 * @since 3.7
	 */
	private IMember[] getSelectedElements() {
		ISelection selection= getSelection();
		if (selection instanceof IStructuredSelection) {
			List<IMember> members= new ArrayList<IMember>();
			List<?> elements= ((IStructuredSelection)selection).toList();
			for (Iterator<?> iter= elements.iterator(); iter.hasNext();) {
				Object obj= iter.next();
				if (obj instanceof MethodWrapper) {
					MethodWrapper wrapper= (MethodWrapper)obj;
					members.add((wrapper).getMember());
				}
			}
			return members.toArray(new IMember[members.size()]);
		}
		return null;
	}

	/**
	 * Gets the selection from the call hierarchy view part.
	 * 
	 * @return the current selection
	 */
	private ISelection getSelection() {
		return fPart.getSelection();
	}

	/**
	 * Checks whether this action can be added for the selected element in the call hierarchy.
	 * 
	 * @return <code> true</code> if the action can be added, <code>false</code> otherwise
	 */
	protected boolean canActionBeAdded() {
		IStructuredSelection selection= (IStructuredSelection)getSelection();
		if (selection.isEmpty())
			return false;

		Iterator<?> iter= selection.iterator();
		while (iter.hasNext()) {
			Object element= iter.next();
			if (!(element instanceof MethodWrapper))//takes care of '...' node
				return false;
		}

		TreeItem[] items= fCallHierarchyViewer.getTree().getSelection();
		for (int k= 0; k < items.length; k++) {
			if (!checkForChildren(items[k]))
				return false;
		}
		return true;
	}

	/**
	 * Checks whether the children are being fetched for a node recursively.
	 * 
	 * @param item the parent node
	 * @return <code>false</code> when children are currently being fetched for a node,
	 *         <code>true</code> otherwise
	 */
	private boolean checkForChildren(TreeItem item) {
		TreeItem[] children= item.getItems();
		if (children.length == 1) {
			Object data= children[0].getData();
			if (!(data instanceof MethodWrapper) && data != null)
				return false; // Do not add action if children are still being fetched for that node or if it's only JFace's dummy node.
		}
		for (int i= 0; i < children.length; i++) {
			if (!checkForChildren(children[i]))
				return false;
		}
		return true;
	}
}
