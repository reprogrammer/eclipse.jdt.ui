/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.code;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.core.filebuffers.ITextFileBuffer;

import org.eclipse.text.edits.TextEdit;

import org.eclipse.jface.text.Document;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.Corext;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.codemanipulation.ImportRewrite;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.dom.HierarchicalASTVisitor;
import org.eclipse.jdt.internal.corext.dom.JavaElementMapper;
import org.eclipse.jdt.internal.corext.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.dom.fragments.ASTFragmentFactory;
import org.eclipse.jdt.internal.corext.dom.fragments.IExpressionFragment;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringScopeFactory;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringSearchEngine2;
import org.eclipse.jdt.internal.corext.refactoring.SearchResultGroup;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaStatusContext;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatusCodes;
import org.eclipse.jdt.internal.corext.refactoring.changes.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.DynamicValidationStateChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChangeCompatibility;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringFileBuffers;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusContext;
import org.eclipse.ltk.core.refactoring.TextChange;

public class InlineConstantRefactoring extends Refactoring {

	private static class InlineTargetCompilationUnit {

		private static class ClassQualification extends StringInsertion {

			public static class ClassQualificationCannotBePerformed extends Exception {

				// not really serializable
				private static final long serialVersionUID= 1L;

				public ClassQualificationCannotBePerformed(String message) {
					super(message);
				}

				public void fillInStatus(RefactoringStatus status, RefactoringStatusContext context) {
					status.addInfo(getMessage(), context);
				}
			}

			public static ClassQualification create(SimpleName toQualify, IntegerMapping nodeToTargetPositionMap) throws ClassQualificationCannotBePerformed {
				ITypeBinding declaringClass= getDeclaringClassBinding(toQualify);
				String declaringClassName= getDeclaringClassName(toQualify);

				if (declaringClassName == null)
					return null;

				return new ClassQualification(declaringClassName, nodeToTargetPositionMap.map(toQualify.getStartPosition()), declaringClass);
			}

			private static String getClassNameQualifiedToTopLevel(ITypeBinding clazz) throws ClassQualificationCannotBePerformed {
				if (clazz.isAnonymous())
					throw new ClassQualificationCannotBePerformed(RefactoringCoreMessages.getString("InlineConstantRefactoring.members_declared_in_anonymous")); //$NON-NLS-1$

				ITypeBinding declaring= clazz.getDeclaringClass();

				String qualifier= declaring == null ? "" : getClassNameQualifiedToTopLevel(declaring) + "."; //$NON-NLS-1$ //$NON-NLS-2$
				return qualifier + clazz.getName();
			}

			private static ITypeBinding getDeclaringClassBinding(SimpleName memberName) {

				IBinding binding= memberName.resolveBinding();
				if (binding instanceof IMethodBinding)
					return ((IMethodBinding) binding).getDeclaringClass();

				if (binding instanceof IVariableBinding)
					return ((IVariableBinding) binding).getDeclaringClass();

				if (binding instanceof ITypeBinding)
					return ((ITypeBinding) binding).getDeclaringClass();

				Assert.isTrue(false);
				return null;

			}

			private static String getDeclaringClassName(SimpleName memberName) throws ClassQualificationCannotBePerformed {
				ITypeBinding declaring= getDeclaringClassBinding(memberName);
				if (declaring == null)
					return null;

				return getClassNameQualifiedToTopLevel(declaring);
			}

			private final ITypeBinding fQualifyingClass;

			private ClassQualification(String qualifier, int insertionPosition, ITypeBinding qualifyingClass) {
				super(insertionPosition, qualifier + "."); //$NON-NLS-1$
				fQualifyingClass= qualifyingClass;
			}

			public ITypeBinding getQualifyingClass() {
				return fQualifyingClass;
			}

		}

		private static class InitializerExpressionRelocationPreparer {

			// ---- Begin InlineTargetCompilationUnit.InitializerExpressionRelocationPreparer.InitializerTraversal
			private static class InitializerTraversal extends HierarchicalASTVisitor {

				private static boolean areInSameClassOrInterface(ASTNode one, ASTNode other) {
					ASTNode onesContainer= getContainingClassOrInterfaceDeclaration(one);
					ASTNode othersContainer= getContainingClassOrInterfaceDeclaration(other);

					if (onesContainer == null || othersContainer == null)
						return false;

					ITypeBinding onesContainerBinding= getTypeBindingForClassOrInterfaceDeclaration(onesContainer);
					ITypeBinding othersContainerBinding= getTypeBindingForClassOrInterfaceDeclaration(othersContainer);

					Assert.isNotNull(onesContainerBinding);
					Assert.isNotNull(othersContainerBinding);

					String onesKey= onesContainerBinding.getKey();
					String othersKey= othersContainerBinding.getKey();

					if (onesKey == null || othersKey == null)
						return false;

					return onesKey.equals(othersKey);
				}

				private static void checkMemberAcceptable(SimpleName memberName) {
					IBinding binding= memberName.resolveBinding();
					Assert.isTrue(binding instanceof IVariableBinding || binding instanceof IMethodBinding || binding instanceof ITypeBinding);

					if (binding instanceof ITypeBinding)
						return;

					if (binding instanceof IVariableBinding)
						Assert.isTrue(((IVariableBinding) binding).isField());

					int modifiers= binding.getModifiers();
					Assert.isTrue(Modifier.isStatic(modifiers), "Relocation of non-static initializer expressions is not currently supported"); //$NON-NLS-1$
				}

				private static ASTNode getContainingClassOrInterfaceDeclaration(ASTNode node) {
					while (node != null && !(node instanceof AbstractTypeDeclaration) && !(node instanceof AnonymousClassDeclaration)) {
						node= node.getParent();
					}
					return node;
				}

				private static ITypeBinding getTypeBindingForClassOrInterfaceDeclaration(ASTNode declaration) {
					if (declaration instanceof AnonymousClassDeclaration)
						return ((AnonymousClassDeclaration) declaration).resolveBinding();

					if (declaration instanceof AbstractTypeDeclaration)
						return ((AbstractTypeDeclaration) declaration).resolveBinding();

					Assert.isTrue(false);
					return null;
				}

				private boolean fCanBePrepared= true;

				private final Expression fInitializer3;// use name other than fInitializer to avoid hiding

				// cache:
				private Set fNamesDeclaredLocallyAtNewLocation;

				private final Expression fNewLocation;

				private final ICompilationUnit fNewLocationCU;

				private List fQualifications= new ArrayList();

				private final RefactoringStatus fStatus2;

				public InitializerTraversal(Expression initializer, Expression newLocation, ICompilationUnit newLocationCU, RefactoringStatus status) {
					fStatus2= status;
					fInitializer3= initializer;
					fNewLocation= newLocation;
					fNewLocationCU= newLocationCU;

					perform(initializer);
				}

				public boolean canInitializerBePrepared() {
					return fCanBePrepared;
				}

				/**
				 * @param scope not a TypeDeclaration
				 * @return Set containing Strings representing simple names
				 */
				private Set getLocallyDeclaredNames(BodyDeclaration scope) {
					Assert.isTrue(!(scope instanceof AbstractTypeDeclaration));

					final Set result= new HashSet();

					if (scope instanceof FieldDeclaration)
						return result;

					scope.accept(new HierarchicalASTVisitor() {

						public boolean visit(AbstractTypeDeclaration node) {
							Assert.isTrue(node.getParent() instanceof TypeDeclarationStatement);

							result.add(node.getName().getIdentifier());
							return false;
						}

						public boolean visit(AnonymousClassDeclaration anonDecl) {
							return false;
						}

						public boolean visit(VariableDeclaration varDecl) {
							result.add(varDecl.getName().getIdentifier());
							return false;
						}
					});
					return result;
				}

				private Set getNamesDeclaredLocallyAtNewLocation() {
					if (fNamesDeclaredLocallyAtNewLocation != null)
						return fNamesDeclaredLocallyAtNewLocation;

					BodyDeclaration enclosingBodyDecl= (BodyDeclaration) ASTNodes.getParent(fNewLocation, BodyDeclaration.class);
					Assert.isTrue(!(enclosingBodyDecl instanceof AbstractTypeDeclaration));

					return fNamesDeclaredLocallyAtNewLocation= getLocallyDeclaredNames(enclosingBodyDecl);
				}

				public ClassQualification[] getQualifications() {
					return (ClassQualification[]) fQualifications.toArray(new ClassQualification[fQualifications.size()]);
				}

				private boolean mayBeShadowedByLocalDeclaration(SimpleName memberName) {
					return getNamesDeclaredLocallyAtNewLocation().contains(memberName.getIdentifier());
				}

				private void perform(Expression initializer) {
					initializer.accept(this);
				}

				private void qualifyMemberName(SimpleName memberName) {
					checkMemberAcceptable(memberName);
					qualifyToTopLevelClass(memberName);
				}

				private void qualifyToTopLevelClass(SimpleName name) {
					try {
						ClassQualification qualification= ClassQualification.create(name, new IntegerMapping() {

							public int map(int position) {
								return position - fInitializer3.getStartPosition();
							}
						});
						if (qualification != null)
							fQualifications.add(qualification);
					} catch (ClassQualification.ClassQualificationCannotBePerformed e) {
						e.fillInStatus(fStatus2, JavaStatusContext.create(fNewLocationCU, fNewLocation));
						fCanBePrepared= false;
					}
				}

				private void qualifyUnqualifiedMemberNameIfNecessary(SimpleName memberName) {
					if (shouldQualify(memberName))
						qualifyMemberName(memberName);
				}

				private boolean shouldQualify(SimpleName memberName) {
					if (!areInSameClassOrInterface(fInitializer3, fNewLocation))
						return true;

					return mayBeShadowedByLocalDeclaration(memberName);
				}

				public boolean visit(ASTNode node) {
					return fCanBePrepared;
				}

				public boolean visit(FieldAccess fieldAccess) {
					fieldAccess.getExpression().accept(this);
					return false;
				}

				public boolean visit(MethodInvocation invocation) {
					if (invocation.getExpression() == null)
						qualifyUnqualifiedMemberNameIfNecessary(invocation.getName());
					else
						invocation.getExpression().accept(this);

					for (Iterator it= invocation.arguments().iterator(); it.hasNext();)
						((Expression) it.next()).accept(this);

					return false;
				}

				public boolean visit(Name name) {
					SimpleName leftmost= getLeftmost(name);

					IBinding leftmostBinding= leftmost.resolveBinding();
					if (leftmostBinding instanceof IVariableBinding || leftmostBinding instanceof IMethodBinding || leftmostBinding instanceof ITypeBinding)
						qualifyUnqualifiedMemberNameIfNecessary(leftmost);

					return false;
				}
			}

			private static String parenthesize(String string) {
				return "(" + string + ")"; //$NON-NLS-1$ //$NON-NLS-2$
			}

			public static String prepareInitializerForLocation(Expression initializer, ICompilationUnit initializerCU, Expression location, ICompilationUnit locationCU, Set newTypes, RefactoringStatus status) throws JavaModelException {
				return new InitializerExpressionRelocationPreparer(initializer, initializerCU, location, locationCU, newTypes, status).prepareInitializer();
			}

			private static boolean shouldParenthesizeSubstitute(Expression substitute, Expression location) {
				if (substitute instanceof Assignment)// for esthetic reasons
					return true;

				return ASTNodes.substituteMustBeParenthesized(substitute, location);
			}

			// ---- End InlineTargetCompilationUnit.InitializerExpressionRelocationPreparer.InitializerTraversal

			private final Expression fInitializer2;// use name other than fInitializer to avoid hiding

			private final ICompilationUnit fInitializerCU;

			private final Expression fLocation;

			private final ICompilationUnit fLocationCU;

			private final Set fNewTypes;

			private final RefactoringStatus fStatus;

			private InitializerExpressionRelocationPreparer(Expression initializer, ICompilationUnit initializerCU, Expression location, ICompilationUnit locationCU, Set newTypes, RefactoringStatus status) {
				fInitializer2= initializer;
				fInitializerCU= initializerCU;
				fLocation= location;
				fLocationCU= locationCU;
				fNewTypes= newTypes;
				fStatus= status;
			}

			private String getOriginalInitializerString() throws JavaModelException {
				return fInitializerCU.getBuffer().getText(fInitializer2.getStartPosition(), fInitializer2.getLength());
			}

			private String prepareInitializer() throws JavaModelException {
				InitializerTraversal traversal= new InitializerTraversal(fInitializer2, fLocation, fLocationCU, fStatus);

				if (!traversal.canInitializerBePrepared())
					return null;

				ClassQualification[] qualifications= traversal.getQualifications();
				for (int i= 0; i < qualifications.length; i++)
					fNewTypes.add(qualifications[i].getQualifyingClass());

				String result= new MultiInsertionStringEdit(qualifications).applyTo(getOriginalInitializerString());

				if (shouldParenthesize())
					result= parenthesize(result);

				return result;
			}

			private boolean shouldParenthesize() {
				return shouldParenthesizeSubstitute(fInitializer2, fLocation);
			}
		}

		private static interface IntegerMapping {

			public int map(int x);
		}

		private static class MultiInsertionStringEdit extends StringEdit {

			private final StringInsertion[] fOrderedInsertions;

			public MultiInsertionStringEdit(StringInsertion[] insertions) {
				orderInsertions(insertions);
				checkInsertions(insertions);
				fOrderedInsertions= insertions;
			}

			public String applyTo(String target) {
				String result= target;
				for (int i= fOrderedInsertions.length - 1; i >= 0; i--) {
					result= fOrderedInsertions[i].applyTo(result);
				}
				return result;
			}

			private void checkInsertions(StringInsertion[] insertions) {
				for (int i= 1; i < insertions.length; i++) {
					StringInsertion one= insertions[i - 1], other= insertions[i];
					Assert.isTrue(one.compareTo(other) != 0);
				}
			}

			private StringInsertion[] orderInsertions(StringInsertion[] insertions) {
				Arrays.sort(insertions);
				return insertions;
			}
		}

		private abstract static class StringEdit {

			public abstract String applyTo(String target);
		}

		private static class StringInsertion extends StringEdit implements Comparable {

			private final int fOffset;

			private final String fToInsert;

			public StringInsertion(int offset, String toInsert) {
				fOffset= offset;
				fToInsert= toInsert;
			}

			public String applyTo(String target) {
				return target.substring(0, fOffset) + fToInsert + target.substring(fOffset);
			}

			/**
			 * 
			 * @see java.lang.Comparable#compareTo(java.lang.Object)
			 */
			public int compareTo(Object other) {
				Assert.isTrue(other instanceof StringInsertion);

				StringInsertion otherInsertion= (StringInsertion) other;

				if (fOffset < otherInsertion.fOffset)
					return -1;
				if (fOffset == otherInsertion.fOffset)
					return 0;
				Assert.isTrue(fOffset > otherInsertion.fOffset);
				return 1;
			}
		}

		private static class TypeReferenceFinder extends HierarchicalASTVisitor {

			public static ITypeBinding[] getReferencedTopLevelTypes(ASTNode tree) {
				return new TypeReferenceFinder().getTopLevelTypesReferenced(tree);
			}

			private List fTypes;

			private ITypeBinding[] getTopLevelTypesReferenced(ASTNode tree) {
				reset();
				tree.accept(this);
				return (ITypeBinding[]) fTypes.toArray(new ITypeBinding[fTypes.size()]);
			}

			private void reset() {
				fTypes= new ArrayList();
			}

			public boolean visit(Name name) {
				SimpleName leftmost= getLeftmost(name);

				IBinding binding= leftmost.resolveBinding();
				if (binding instanceof ITypeBinding)
					fTypes.add(binding);

				return false;
			}
		}

		private static boolean doesParentQualify(Name fieldName) {
			ASTNode parent= fieldName.getParent();
			Assert.isNotNull(parent);

			if (parent instanceof FieldAccess && ((FieldAccess) parent).getName() == fieldName)
				return true;

			if (parent instanceof QualifiedName && ((QualifiedName) parent).getName() == fieldName)
				return true;

			if (parent instanceof MethodInvocation && ((MethodInvocation) parent).getName() == fieldName)
				return true;

			return false;
		}

		private static InlineTargetCompilationUnit[] findTargetsForAllReferences(InlineConstantRefactoring refactoring, IProgressMonitor pm, RefactoringStatus status) throws JavaModelException {
			IField field= refactoring.getField();
			Assert.isNotNull(field);

			SearchResultGroup[] searchResultGroups= search(field, pm, status);

			InlineTargetCompilationUnit[] result= new InlineTargetCompilationUnit[searchResultGroups.length];
			for (int i= 0; i < searchResultGroups.length; i++)
				result[i]= new InlineTargetCompilationUnit(searchResultGroups[i], refactoring.getInitializer(), refactoring.getDeclaringCompilationUnit(), refactoring.fSettings);

			return result;
		}

		private static Expression getQualifiedReference(Name fieldName) {
			if (doesParentQualify(fieldName))
				return (Expression) fieldName.getParent();

			return fieldName;
		}

		private static InlineTargetCompilationUnit getTargetForOnlySelectedReference(InlineConstantRefactoring refactoring) throws JavaModelException {
			Assert.isTrue(!refactoring.isDeclarationSelected());
			return new InlineTargetCompilationUnit(refactoring.fCu, refactoring.getConstantNameNode(), refactoring.getInitializer(), refactoring.getDeclaringCompilationUnit(), refactoring.fSettings);
		}

		public static InlineTargetCompilationUnit[] prepareTargets(InlineConstantRefactoring refactoring, IProgressMonitor pm, RefactoringStatus status) throws JavaModelException, CoreException {
			Assert.isNotNull(refactoring);
			Assert.isNotNull(pm);

			InlineTargetCompilationUnit[] results= prepareTargetsUnchecked(refactoring, pm, status);
			return results;
		}

		private static InlineTargetCompilationUnit[] prepareTargetsUnchecked(InlineConstantRefactoring refactoring, IProgressMonitor pm, RefactoringStatus status) throws JavaModelException {
			if (refactoring.getReplaceAllReferences())
				return findTargetsForAllReferences(refactoring, pm, status);
			else
				return new InlineTargetCompilationUnit[] { getTargetForOnlySelectedReference(refactoring)};
		}

		private static SearchResultGroup[] search(IField field, IProgressMonitor pm, RefactoringStatus status) throws JavaModelException {
			Assert.isNotNull(pm);
			Assert.isNotNull(field);
			final RefactoringSearchEngine2 engine= new RefactoringSearchEngine2(SearchPattern.createPattern(field, IJavaSearchConstants.REFERENCES));
			engine.setFiltering(true, true);
			engine.setScope(RefactoringScopeFactory.create(field));
			engine.setStatus(status);
			engine.searchPattern(new SubProgressMonitor(pm, 1));
			return (SearchResultGroup[]) engine.getResults();
		}

		private RefactoringStatus fEditProblems;

		private ImportRewrite fImportRewrite;

		private final Expression fInitializer;

		private final ICompilationUnit fInitializerUnit;

		private List fInlineEdits;

		/** The references in this compilation unit, represented as AST Nodes in the parsed representation of the compilation unit */
		private final Expression[] fReferences;

		private boolean fSomeReferencesCannotBeInlined;

		private final ICompilationUnit fUnit;

		private InlineTargetCompilationUnit(ICompilationUnit cu, Name singleReference, Expression initializer, ICompilationUnit initializerUnit, CodeGenerationSettings codeGenSettings) {
			Assert.isNotNull(initializer);
			Assert.isNotNull(singleReference);
			Assert.isNotNull(cu);
			Assert.isTrue(cu.exists());
			Assert.isNotNull(initializerUnit);
			Assert.isTrue(initializerUnit.exists());
			Assert.isNotNull(codeGenSettings);

			fReferences= new Expression[] { getQualifiedReference(singleReference)};
			fUnit= cu;
			fInitializer= initializer;
			fInitializerUnit= initializerUnit;
		}

		private InlineTargetCompilationUnit(ICompilationUnit cu, SearchMatch[] references, Expression initializer, ICompilationUnit initializerUnit, CodeGenerationSettings codeGenSettings) {
			Assert.isNotNull(initializer);
			Assert.isNotNull(references);
			Assert.isTrue(references.length > 0);
			Assert.isNotNull(cu);
			Assert.isTrue(cu.exists());
			Assert.isNotNull(codeGenSettings);

			fUnit= cu;
			fInitializer= initializer;
			fInitializerUnit= initializerUnit;

			fReferences= new Expression[references.length];

			CompilationUnit cuNode= new RefactoringASTParser(AST.JLS3).parse(cu, true);
			for (int i= 0; i < references.length; i++) {
				ASTNode node= NodeFinder.perform(cuNode, references[i].getOffset(), references[i].getLength());
				Assert.isTrue(node instanceof Name);
				fReferences[i]= getQualifiedReference((Name) node);
			}

		}

		private InlineTargetCompilationUnit(SearchResultGroup group, Expression initializer, ICompilationUnit initializerUnit, CodeGenerationSettings codeGenSettings) {
			this(group.getCompilationUnit(), group.getSearchResults(), initializer, initializerUnit, codeGenSettings);
		}

		private void addEditsToInline(Expression reference) throws CoreException {
			Set newTypes= new HashSet();

			String modifiedInitializer= prepareInitializerFor(reference, newTypes, fEditProblems);

			if (modifiedInitializer == null)
				return;

			fInlineEdits.add(createSubstituteStringForExpressionEdit(modifiedInitializer, reference));
			addImportsForTypesInOriginalInitializer();
			addImportsForNewTypes(newTypes);
		}

		private void addImportForType(ITypeBinding type) {
			fImportRewrite.addImport(type);
		}

		private void addImportsForNewTypes(Set newTypes) {
			for (Iterator it= newTypes.iterator(); it.hasNext();)
				addImportForType((ITypeBinding) it.next());
		}

		private void addImportsForTypesInOriginalInitializer() {
			ITypeBinding[] types= TypeReferenceFinder.getReferencedTopLevelTypes(fInitializer);
			for (int i= 0; i < types.length; i++)
				addImportForType(types[i]);
		}

		public boolean checkReferences(RefactoringStatus result) throws CoreException {
			Assert.isNotNull(result);

			getEdits(result);
			return fSomeReferencesCannotBeInlined;
		}

		private TextEdit createSubstituteStringForExpressionEdit(String string, Expression expression) throws CoreException {
			ASTRewrite rewrite= ASTRewrite.create(expression.getRoot().getAST());

			rewrite.replace(expression, rewrite.createStringPlaceholder(string, expression.getNodeType()), null);

			try {
				final ITextFileBuffer buffer= RefactoringFileBuffers.acquire(fUnit);
				return rewrite.rewriteAST(buffer.getDocument(), fInitializerUnit.getJavaProject().getOptions(true));
			} finally {
				RefactoringFileBuffers.release(fUnit);
			}
		}

		private List getAllEdits() throws CoreException {
			List allEdits= new ArrayList(fInlineEdits);
			if (!fImportRewrite.isEmpty()) {
				allEdits.add(fImportRewrite.createEdit(new Document(fImportRewrite.getCompilationUnit().getBuffer().getContents())));
			}
			return allEdits;
		}

		private TextEdit[] getAllEditsAsArray() throws CoreException {
			List allEdits= getAllEdits();
			return (TextEdit[]) allEdits.toArray(new TextEdit[allEdits.size()]);
		}

		public TextChange getChange() throws CoreException {
			return getChange(new RefactoringStatus());
		}

		public TextChange getChange(RefactoringStatus status) throws CoreException {
			TextChange change= new CompilationUnitChange(fUnit.getElementName(), fUnit);
			TextEdit[] edits= getEdits(status);
			for (int i= 0; i < edits.length; i++)
				TextChangeCompatibility.addTextEdit(change, RefactoringCoreMessages.getString("InlineConstantRefactoring.Inline"), edits[i]); //$NON-NLS-1$
			return change;
		}

		public TextEdit[] getEdits() throws CoreException {
			return getEdits(new RefactoringStatus());
		}

		public TextEdit[] getEdits(RefactoringStatus status) throws CoreException {
			if (fInlineEdits != null) {
				Assert.isNotNull(fImportRewrite);
				Assert.isNotNull(fEditProblems);

				status.merge(fEditProblems);
				return getAllEditsAsArray();
			}

			fInlineEdits= new ArrayList();
			fImportRewrite= new ImportRewrite(fUnit);
			fEditProblems= new RefactoringStatus();

			if (fUnit.getSource() == null) {
				String[] keys= { fUnit.getElementName()};
				String msg= RefactoringCoreMessages.getFormattedString("InlineConstantRefactoring.source_code_unavailable", keys); //$NON-NLS-1$
				fEditProblems.merge(RefactoringStatus.createStatus(RefactoringStatus.INFO, msg, null, Corext.getPluginId(), RefactoringStatusCodes.REFERENCE_IN_CLASSFILE, null));
			} else {
				for (int i= 0; i < fReferences.length; i++)
					addEditsToInline(fReferences[i]);
			}

			status.merge(fEditProblems);
			return getAllEditsAsArray();
		}

		private String prepareInitializerFor(Expression reference, Set newTypes, RefactoringStatus status) throws JavaModelException {
			return InitializerExpressionRelocationPreparer.prepareInitializerForLocation(fInitializer, fInitializerUnit, reference, fUnit, newTypes, status);
		}
	}

	// ---- End InlineTargetCompilationUnit ----------------------------------------------------------------------------------------------

	public static InlineConstantRefactoring create(ICompilationUnit cu, int selectionStart, int selectionLength, CodeGenerationSettings settings) {
		InlineConstantRefactoring ref= new InlineConstantRefactoring(cu, selectionStart, selectionLength, settings);
		if (ref.checkStaticFinalConstantNameSelected().hasFatalError())
			return null;
		return ref;
	}

	private static SimpleName getLeftmost(Name name) {
		if (name instanceof SimpleName)
			return (SimpleName) name;

		return getLeftmost(((QualifiedName) name).getQualifier());
	}

	public static boolean isAvailable(IField field) throws JavaModelException {
		return Checks.isAvailable(field) && JdtFlags.isStatic(field) && JdtFlags.isFinal(field) && !JdtFlags.isEnum(field);
	}

	private CompilationUnit fCompilationUnitNode;

	private final ICompilationUnit fCu;

	private boolean fDeclarationSelected;

	private boolean fDeclarationSelectedChecked= false;

	private Expression fInitializer;

	private boolean fInitializerAllStaticFinal;

	private boolean fInitializerChecked= false;

	private boolean fInitializerFound= false;

	private boolean fRemoveDeclaration= false;

	private boolean fReplaceAllReferences= true;

	private final int fSelectionLength;

	private final int fSelectionStart;

	private final CodeGenerationSettings fSettings;

	private InlineTargetCompilationUnit[] fTargetCompilationUnits;

	private InlineConstantRefactoring(ICompilationUnit cu, int selectionStart, int selectionLength, CodeGenerationSettings settings) {
		Assert.isTrue(selectionStart >= 0);
		Assert.isTrue(selectionLength >= 0);
		Assert.isTrue(cu.exists());
		Assert.isNotNull(settings);

		fSelectionStart= selectionStart;
		fSelectionLength= selectionLength;
		fCu= cu;
		fSettings= settings;
	}

	private void addRemoveConstantDeclarationIfNecessary(List changes) throws CoreException {
		TextEdit edit= getRemoveConstantDeclarationEdit();
		if (edit == null)
			return;

		TextChange change= findOrAddDeclaringCUChange(changes);
		TextChangeCompatibility.addTextEdit(change, RefactoringCoreMessages.getString("InlineConstantRefactoring.remove_declaration"), edit); //$NON-NLS-1$
	}

	private void addReplaceReferencesWithExpression(List changes) throws JavaModelException, CoreException {
		for (int i= 0; i < fTargetCompilationUnits.length; i++)
			changes.add(fTargetCompilationUnits[i].getChange());
	}

	private void checkDeclarationSelected() {
		Assert.isNotNull(getConstantNameNode());

		fDeclarationSelected= false;
		VariableDeclarationFragment parentDeclaration= getParentDeclaration();
		if (parentDeclaration != null && parentDeclaration.getName() == getConstantNameNode())
			fDeclarationSelected= true;
		fDeclarationSelectedChecked= true;
	}

	public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException {
		RefactoringStatus result= new RefactoringStatus();

		fTargetCompilationUnits= InlineTargetCompilationUnit.prepareTargets(this, pm, result);

		for (int i= 0; i < fTargetCompilationUnits.length; i++)
			fTargetCompilationUnits[i].checkReferences(result);

		if (result.hasFatalError())
			return result;

		ICompilationUnit[] cus= new ICompilationUnit[fTargetCompilationUnits.length + 1];
		for (int i= 0; i < fTargetCompilationUnits.length; i++) {
			cus[i]= fTargetCompilationUnits[i].fUnit;
		}
		cus[cus.length - 1]= fCu;
		result.merge(Checks.validateModifiesFiles(ResourceUtil.getFiles(cus), getValidationContext()));

		return result;
	}

	public RefactoringStatus checkInitialConditions(IProgressMonitor pm) throws CoreException {
		try {
			pm.beginTask("", 4); //$NON-NLS-1$

			if (!fCu.isStructureKnown())
				return RefactoringStatus.createStatus(RefactoringStatus.FATAL, RefactoringCoreMessages.getString("InlineConstantRefactoring.syntax_errors"), null, Corext.getPluginId(), RefactoringStatusCodes.SYNTAX_ERRORS, null); //$NON-NLS-1$
			pm.worked(1);

			return checkSelection(new SubProgressMonitor(pm, 2));
		} finally {
			pm.done();
		}
	}

	private RefactoringStatus checkInitializer() {
		Expression initializer= getInitializer();
		if (initializer == null)
			return RefactoringStatus.createStatus(RefactoringStatus.FATAL, RefactoringCoreMessages.getString("InlineConstantRefactoring.blank_finals"), null, Corext.getPluginId(), RefactoringStatusCodes.CANNOT_INLINE_BLANK_FINAL, null); //$NON-NLS-1$

		fInitializerAllStaticFinal= ConstantChecks.isStaticFinalConstant((IExpressionFragment) ASTFragmentFactory.createFragmentForFullSubtree(initializer));
		fInitializerChecked= true;
		return new RefactoringStatus();
	}

	private void checkInvariant() {
		if (isDeclarationSelected())
			Assert.isTrue(fReplaceAllReferences);
		if (fRemoveDeclaration)
			Assert.isTrue(fReplaceAllReferences);
	}

	private RefactoringStatus checkSelection(IProgressMonitor pm) throws JavaModelException {
		try {
			RefactoringStatus result= checkStaticFinalConstantNameSelected();
			if (result.hasFatalError())
				return result;
			pm.worked(1);

			/*
			 * For now, we don't perform the inline if getField() == null. XXX: Handle constants with no IField f for which f.exists():
			 */
			if (getField() == null)
				return RefactoringStatus.createStatus(RefactoringStatus.FATAL, RefactoringCoreMessages.getString("InlineConstantRefactoring.local_anonymous_unsupported"), null, Corext.getPluginId(), RefactoringStatusCodes.LOCAL_AND_ANONYMOUS_NOT_SUPPORTED, null); //$NON-NLS-1$

			checkDeclarationSelected();

			result.merge(findInitializer());
			if (result.hasFatalError())
				return result;

			result.merge(checkInitializer());
			if (result.hasFatalError())
				return result;
			pm.worked(1);

			return result;
		} finally {
			pm.done();
		}
	}

	private RefactoringStatus checkStaticFinalConstantNameSelected() {
		fCompilationUnitNode= new RefactoringASTParser(AST.JLS3).parse(fCu, true);

		if (getConstantNameNode() == null)
			return RefactoringStatus.createStatus(RefactoringStatus.FATAL, RefactoringCoreMessages.getString("InlineConstantRefactoring.static_final_field"), null, Corext.getPluginId(), RefactoringStatusCodes.NOT_STATIC_FINAL_SELECTED, null); //$NON-NLS-1$

		return new RefactoringStatus();
	}

	public Change createChange(IProgressMonitor pm) throws CoreException {
		try {
			pm.beginTask(RefactoringCoreMessages.getString("InlineConstantRefactoring.preview"), 2); //$NON-NLS-1$

			Change[] cuChanges= createCompilationUnitChanges(pm);

			final DynamicValidationStateChange result= new DynamicValidationStateChange(RefactoringCoreMessages.getString("InlineConstantRefactoring.inline")); //$NON-NLS-1$
			result.addAll(cuChanges);
			return result;
		} finally {
			pm.done();
		}
	}

	private CompilationUnitChange[] createCompilationUnitChanges(IProgressMonitor pm) throws JavaModelException, CoreException {
		List changes= new ArrayList();
		addReplaceReferencesWithExpression(changes);
		pm.worked(1);
		addRemoveConstantDeclarationIfNecessary(changes);
		pm.worked(1);
		return (CompilationUnitChange[]) changes.toArray(new CompilationUnitChange[changes.size()]);
	}

	private RefactoringStatus findInitializer() throws JavaModelException {
		VariableDeclarationFragment declaration= getDeclaration();
		if (declaration == null)
			return RefactoringStatus.createStatus(RefactoringStatus.FATAL, RefactoringCoreMessages.getString("InlineConstantRefactoring.binary_file"), null, Corext.getPluginId(), RefactoringStatusCodes.DECLARED_IN_CLASSFILE, null); //$NON-NLS-1$

		fInitializer= declaration.getInitializer();
		fInitializerFound= true;
		return new RefactoringStatus();
	}

	private TextChange findOrAddDeclaringCUChange(List changes) throws JavaModelException, CoreException {
		ICompilationUnit declaringCU= getDeclaringCompilationUnit();
		Assert.isNotNull(declaringCU);
		Assert.isTrue(declaringCU.exists());

		for (Iterator it= changes.iterator(); it.hasNext();) {
			CompilationUnitChange change= (CompilationUnitChange) it.next();
			if (change.getCompilationUnit().equals(declaringCU))
				return change;
		}

		CompilationUnitChange newChange= new CompilationUnitChange(declaringCU.getElementName(), declaringCU);
		changes.add(newChange);
		return newChange;
	}

	private Name getConstantNameNode() {
		ASTNode node= NodeFinder.perform(fCompilationUnitNode, fSelectionStart, fSelectionLength);
		if (node == null)
			return null;
		if (node instanceof FieldAccess)
			node= ((FieldAccess) node).getName();
		if (node.getParent() instanceof EnumConstantDeclaration)
			return null;
		if (!(node instanceof Name))
			return null;
		Name name= (Name) node;
		IBinding binding= name.resolveBinding();
		if (!(binding instanceof IVariableBinding))
			return null;
		if (!((IVariableBinding) binding).isField())
			return null;
		int modifiers= binding.getModifiers();
		if (!(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)))
			return null;

		return name;
	}

	/*
	 * Returns the variable declaration fragment corresponding to the selected static final field name, or null if the constant is declared in a class file.
	 */
	private VariableDeclarationFragment getDeclaration() throws JavaModelException {
		Assert.isNotNull(getConstantNameNode());

		VariableDeclarationFragment result;

		if (isDeclarationSelected()) {
			result= getParentDeclaration();
			Assert.isNotNull(result);
			return result;
		}

		result= (VariableDeclarationFragment) fCompilationUnitNode.findDeclaringNode(getConstantNameNode().resolveBinding());
		if (result != null)
			return result;

		if (getDeclaringCompilationUnit() == null)
			return null;

		IField field= getField();
		// Until we support constants with no IField:
		Assert.isNotNull(field);
		Assert.isTrue(field.exists());

		result= (VariableDeclarationFragment) JavaElementMapper.perform(field, VariableDeclarationFragment.class);
		Assert.isNotNull(result);
		return result;
	}

	private ICompilationUnit getDeclaringCompilationUnit() throws JavaModelException {
		IField field= getField();

		// Until we support constants with no IField:
		Assert.isNotNull(field);
		Assert.isTrue(field.exists());

		return field.getCompilationUnit();
	}

	private IField getField() throws JavaModelException {
		Assert.isNotNull(getConstantNameNode());
		IField result= Bindings.findField((IVariableBinding) getConstantNameNode().resolveBinding(), fCu.getJavaProject());
		if (result != null && !result.exists())
			return null;

		return result;
	}

	/*
	 * Returns the constant's initializer, or null if the constant has no initializer at its declaration.
	 */
	private Expression getInitializer() {
		Assert.isTrue(fInitializerFound);
		return fInitializer;
	}

	public String getName() {
		return RefactoringCoreMessages.getString("InlineConstantRefactoring.name"); //$NON-NLS-1$
	}

	private ASTNode getNodeToRemoveForConstantDeclarationRemoval() throws JavaModelException {
		VariableDeclarationFragment declaration= getDeclaration();
		Assert.isNotNull(declaration);

		ASTNode parent= declaration.getParent();
		Assert.isTrue(parent instanceof FieldDeclaration);
		FieldDeclaration parentDeclaration= (FieldDeclaration) parent;
		if (parentDeclaration.fragments().size() == 1)
			return parentDeclaration;

		return declaration;
	}

	private VariableDeclarationFragment getParentDeclaration() {
		Assert.isNotNull(getConstantNameNode());
		ASTNode parent= getConstantNameNode().getParent();
		if (parent instanceof VariableDeclarationFragment)
			return (VariableDeclarationFragment) parent;
		else
			return null;
	}

	private TextEdit getRemoveConstantDeclarationEdit() throws CoreException {
		if (!getRemoveDeclaration())
			return null;

		ASTNode toRemove= getNodeToRemoveForConstantDeclarationRemoval();
		Assert.isNotNull(toRemove);

		ASTRewrite rewrite= ASTRewrite.create(toRemove.getRoot().getAST());

		rewrite.remove(toRemove, null);

		try {
			final ITextFileBuffer buffer= RefactoringFileBuffers.acquire(getDeclaringCompilationUnit());
			return rewrite.rewriteAST(buffer.getDocument(), getDeclaringCompilationUnit().getJavaProject().getOptions(true));
		} finally {
			RefactoringFileBuffers.release(getDeclaringCompilationUnit());
		}
	}

	public boolean getRemoveDeclaration() {
		checkInvariant();
		return fRemoveDeclaration;
	}

	public boolean getReplaceAllReferences() {
		checkInvariant();
		return fReplaceAllReferences;
	}

	public boolean isDeclarationSelected() {
		Assert.isTrue(fDeclarationSelectedChecked);
		return fDeclarationSelected;
	}

	public boolean isInitializerAllStaticFinal() {
		Assert.isTrue(fInitializerChecked);
		return fInitializerAllStaticFinal;
	}

	public void setRemoveDeclaration(boolean removeDeclaration) {
		fRemoveDeclaration= removeDeclaration;
		checkInvariant();
	}

	public void setReplaceAllReferences(boolean replaceAllReferences) {
		fReplaceAllReferences= replaceAllReferences;
		checkInvariant();
	}
}