/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.wizards;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;

import org.eclipse.swt.widgets.Label;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.IStructuredSelection;

import org.eclipse.ui.help.DialogPageContextComputer;
import org.eclipse.ui.help.WorkbenchHelp;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

import org.eclipse.jdt.internal.ui.IJavaHelpContextIds;
import org.eclipse.jdt.internal.corext.codemanipulation.IImportsStructure;
import org.eclipse.jdt.internal.ui.dialogs.StatusUtil;
import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility;
import org.eclipse.jdt.internal.ui.wizards.dialogfields.DialogField;
import org.eclipse.jdt.internal.ui.wizards.dialogfields.LayoutUtil;
import org.eclipse.jdt.internal.ui.wizards.dialogfields.SelectionButtonDialogFieldGroup;
import org.eclipse.jdt.internal.ui.wizards.swt.MGridData;
import org.eclipse.jdt.internal.ui.wizards.swt.MGridLayout;


public class NewClassCreationWizardPage extends TypePage {
	
	private final static String PAGE_NAME= "NewClassCreationWizardPage"; //$NON-NLS-1$
	
	private final static String SETTINGS_CREATEMAIN= "create_main"; //$NON-NLS-1$
	private final static String SETTINGS_CREATECONSTR= "create_constructor"; //$NON-NLS-1$
	private final static String SETTINGS_CREATEUNIMPLEMENTED= "create_unimplemented"; //$NON-NLS-1$
	
	private SelectionButtonDialogFieldGroup fMethodStubsButtons;
	
	public NewClassCreationWizardPage(IWorkspaceRoot root) {
		super(true, PAGE_NAME, root);
		
		setTitle(NewWizardMessages.getString("NewClassCreationWizardPage.title")); //$NON-NLS-1$
		setDescription(NewWizardMessages.getString("NewClassCreationWizardPage.description")); //$NON-NLS-1$
		
		String[] buttonNames3= new String[] {
			NewWizardMessages.getString("NewClassCreationWizardPage.methods.main"), NewWizardMessages.getString("NewClassCreationWizardPage.methods.constructors"), //$NON-NLS-1$ //$NON-NLS-2$
			NewWizardMessages.getString("NewClassCreationWizardPage.methods.inherited") //$NON-NLS-1$
		};		
		fMethodStubsButtons= new SelectionButtonDialogFieldGroup(SWT.CHECK, buttonNames3, 1);
		fMethodStubsButtons.setLabelText(NewWizardMessages.getString("NewClassCreationWizardPage.methods.label"));		 //$NON-NLS-1$
	}

	// -------- Initialization ---------

	/**
	 * Should be called from the wizard with the input element.
	 */
	public void init(IStructuredSelection selection) {
		IJavaElement jelem= getInitialJavaElement(selection);

		initContainerPage(jelem);
		initTypePage(jelem);
		updateStatus(findMostSevereStatus());
		
		boolean createMain= false;
		boolean createConstructors= false;
		boolean createUnimplemented= true;
		IDialogSettings section= getDialogSettings().getSection(PAGE_NAME);
		if (section != null) {
			createMain= section.getBoolean(SETTINGS_CREATEMAIN);
			createConstructors= section.getBoolean(SETTINGS_CREATECONSTR);
			createUnimplemented= section.getBoolean(SETTINGS_CREATEUNIMPLEMENTED);
		}
		fMethodStubsButtons.setSelection(0, createMain);
		fMethodStubsButtons.setSelection(1, createConstructors);
		fMethodStubsButtons.setSelection(2, createUnimplemented);
	}

	// ------ validation --------
	
	/**
	 * Finds the most severe error (if there is one)
	 */
	private IStatus findMostSevereStatus() {
		return StatusUtil.getMostSevere(new IStatus[] {
			fContainerStatus,
			isEnclosingTypeSelected() ? fEnclosingTypeStatus : fPackageStatus,
			fTypeNameStatus,
			fModifierStatus,
			fSuperClassStatus,
			fSuperInterfacesStatus
		});
	}
	
	/*
	 * @see ContainerPage#handleFieldChanged
	 */
	protected void handleFieldChanged(String fieldName) {
		super.handleFieldChanged(fieldName);
		
		updateStatus(findMostSevereStatus());
	}
	
	
	// ------ ui --------
	
	/*
	 * @see WizardPage#createControl
	 */
	public void createControl(Composite parent) {
		initializeDialogUnits(parent);
		
		Composite composite= new Composite(parent, SWT.NONE);
		
		int nColumns= 4;
		
		MGridLayout layout= new MGridLayout();
		layout.minimumWidth= convertWidthInCharsToPixels(80);
		layout.numColumns= nColumns;		
		composite.setLayout(layout);
		
		createContainerControls(composite, nColumns);	
		createPackageControls(composite, nColumns);	
		createEnclosingTypeControls(composite, nColumns);
				
		createSeparator(composite, nColumns);
		
		createTypeNameControls(composite, nColumns);
		createModifierControls(composite, nColumns);

		//// createSeparator(composite, nColumns);
				
		createSuperClassControls(composite, nColumns);
		createSuperInterfacesControls(composite, nColumns);
				
		////createSeparator(composite, nColumns);
		
		createMethodStubSelectionControls(composite, nColumns);
		
		setControl(composite);
			
		setFocus();
		WorkbenchHelp.setHelp(composite, new DialogPageContextComputer(this, IJavaHelpContextIds.NEW_CLASS_WIZARD_PAGE));	
	}
	
	protected void createMethodStubSelectionControls(Composite composite, int nColumns) {
		LayoutUtil.setHorizontalSpan(fMethodStubsButtons.getLabelControl(composite), nColumns);
		DialogField.createEmptySpace(composite);
		LayoutUtil.setHorizontalSpan(fMethodStubsButtons.getSelectionButtonsGroup(composite), nColumns - 1);	
	}	
	
	// ---- creation ----------------
	
	/*
	 * @see TypePage#evalMethods
	 */
	protected String[] evalMethods(IType type, IImportsStructure imports, IProgressMonitor monitor) throws CoreException {
		List newMethods= new ArrayList();
		
		boolean doMain= fMethodStubsButtons.isSelected(0);
		boolean doConstr= fMethodStubsButtons.isSelected(1);
		boolean doInherited= fMethodStubsButtons.isSelected(2);
		String[] meth= constructInheritedMethods(type, doConstr, doInherited, imports, new SubProgressMonitor(monitor, 1));
		for (int i= 0; i < meth.length; i++) {
			newMethods.add(meth[i]);
		}
		if (monitor != null) {
			monitor.done();
		}
		
		if (doMain) {
			String main= "public static void main(String[] args) {}"; //$NON-NLS-1$
			newMethods.add(main);
		}
		
		IDialogSettings section= getDialogSettings().getSection(PAGE_NAME);
		if (section == null) {
			section= getDialogSettings().addNewSection(PAGE_NAME);
		}
		section.put(SETTINGS_CREATEMAIN, doMain);
		section.put(SETTINGS_CREATECONSTR, doConstr);
		section.put(SETTINGS_CREATEUNIMPLEMENTED, doInherited);	
		
		return (String[]) newMethods.toArray(new String[newMethods.size()]);
	}
	
}