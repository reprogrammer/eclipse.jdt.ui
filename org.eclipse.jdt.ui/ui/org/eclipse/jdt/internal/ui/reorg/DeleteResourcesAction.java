package org.eclipse.jdt.internal.ui.reorg;

import java.util.Iterator;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.MultiStatus;

import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;

import org.eclipse.ui.IWorkbenchSite;
import org.eclipse.ui.actions.DeleteResourceAction;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;

import org.eclipse.jdt.ui.actions.SelectionDispatchAction;

import org.eclipse.jdt.internal.corext.refactoring.Assert;
import org.eclipse.jdt.internal.corext.refactoring.reorg.DeleteRefactoring;
import org.eclipse.jdt.internal.corext.refactoring.reorg.ReorgUtils;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.util.ExceptionHandler;

public class DeleteResourcesAction extends SelectionDispatchAction {

	protected DeleteResourcesAction(IWorkbenchSite site) {
		super(site);
	}

	/*
	 * @see SelectionDispatchAction#run(IStructuredSelection)
	 */
	protected void run(IStructuredSelection selection) {
		if (ClipboardActionUtil.hasOnlyProjects(selection)){
			deleteProjects(selection);
			return;
		}	

		DeleteRefactoring refactoring= new DeleteRefactoring(selection.toList());
		
		if (!confirmDelete(selection))
			return;

		if (hasReadOnlyResources(selection) && !isOkToDeleteReadOnly()) 
			return;

		try{
			
			if (! confirmDeleteSourceFolderAsSubresource(selection))	
				return;
			MultiStatus status= ClipboardActionUtil.perform(refactoring);
			if (!status.isOK()) {
				JavaPlugin.log(status);
				ErrorDialog.openError(JavaPlugin.getActiveWorkbenchShell(), ReorgMessages.getString("DeleteResourceAction.delete"), ReorgMessages.getString("DeleteResourceAction.exception"), status); //$NON-NLS-1$ //$NON-NLS-2$
			}
		} catch (CoreException e){
			ExceptionHandler.handle(e, ReorgMessages.getString("DeleteResourceAction.delete"), ReorgMessages.getString("DeleteResourceAction.exception")); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}	
	}

	private void deleteProjects(IStructuredSelection selection){
		DeleteResourceAction action= new DeleteResourceAction(JavaPlugin.getActiveWorkbenchShell());
		action.selectionChanged(selection);
		action.run();
	}
	
	private static boolean isOkToDeleteReadOnly(){
			String msg= ReorgMessages.getString("deleteAction.confirmReadOnly"); //$NON-NLS-1$
			String title= ReorgMessages.getString("deleteAction.checkDeletion"); //$NON-NLS-1$
			return MessageDialog.openQuestion(
					JavaPlugin.getActiveWorkbenchShell(),
					title,
					msg);
	}
	
	private boolean hasReadOnlyResources(IStructuredSelection selection){
		for (Iterator iter= selection.iterator(); iter.hasNext();){	
			if (ReorgUtils.shouldConfirmReadOnly(iter.next()))
				return true;
		}
		return false;
	}
	
	private static boolean confirmDeleteSourceFolderAsSubresource(IStructuredSelection selection) throws CoreException {
		if (! containsSourceFolderAsSubresource(selection))
			return true;
		String title= ReorgMessages.getString("deleteAction.confirm.title"); //$NON-NLS-1$
		String label= "The selection includes a folder that contains a Java source folder. Delete it as well?";
		return MessageDialog.openQuestion(JavaPlugin.getActiveWorkbenchShell(), title, label);		
	}
	
	private static boolean containsSourceFolderAsSubresource(IStructuredSelection selection) throws CoreException{
		for (Iterator iter= selection.iterator(); iter.hasNext();){	
			Object each= iter.next();
			if (each instanceof IFolder && containsSourceFolder((IFolder)each))
				return true;
		}
		return false;
	}
	
	private static boolean containsSourceFolder(IFolder folder) throws CoreException{
		IResource[] subFolders= folder.members();
		for (int i = 0; i < subFolders.length; i++) {
			if (! (subFolders[i] instanceof IFolder))
				continue;
			IJavaElement element= JavaCore.create((IFolder)folder);
			if (element instanceof IPackageFragmentRoot)	
				return true;
			if (element instanceof IPackageFragment)	
				continue;
			if (containsSourceFolder((IFolder)subFolders[i]))
				return true;
		}
		return false;
	}
	
	
	/*
	 * @see SelectionDispatchAction#selectionChanged(IStructuredSelection)
	 */
	protected void selectionChanged(IStructuredSelection selection) {
		setEnabled(ClipboardActionUtil.canActivate(new DeleteRefactoring(selection.toList())));
	}
	
	private static boolean confirmDelete(IStructuredSelection selection) {
		Assert.isTrue(ClipboardActionUtil.getSelectedProjects(selection).isEmpty());
		String title= ReorgMessages.getString("deleteAction.confirm.title"); //$NON-NLS-1$
		String label= ReorgMessages.getString("deleteAction.confirm.message"); //$NON-NLS-1$
		return MessageDialog.openQuestion(JavaPlugin.getActiveWorkbenchShell(), title, label);
	}
}
