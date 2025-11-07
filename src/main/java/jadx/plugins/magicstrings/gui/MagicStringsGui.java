/**
 * GUI component for displaying and interacting with extracted string data.
 * 
 * This class provides a comprehensive GUI interface for the Magic Strings plugin,
 * including:
 * - Dialog window with multiple tabs for different views of the data
 * - Source Files tab: Tree view of detected source file paths
 * - Top Candidates + Source Files tab: Combined view of high-scoring candidates
 * - Method Candidates tab: Table of filtered method name candidates
 * - All Strings tab: Complete list of all extracted strings
 * 
 * Features include:
 * - Search/filter functionality across all tabs
 * - Method renaming directly from the GUI
 * - Navigation to methods and classes in JADX
 * - Context menus for various actions
 * - Keyboard shortcuts (Ctrl+F for search)
 * 
 * @author 0rshemesh
 * @license Apache License 2.0
 */
package jadx.plugins.magicstrings.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;
import jadx.api.JavaMethod;
import jadx.api.data.ICodeData;
import jadx.api.data.ICodeRename;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.core.deobf.NameMapper;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.plugins.magicstrings.data.MagicStringsData;

public class MagicStringsGui {
	private static final Logger LOG = LoggerFactory.getLogger(MagicStringsGui.class);

	private final JadxPluginContext pluginContext;
	// Cache for method names to avoid repeated expensive lookups
	private final Map<String, String> methodNameCache = new ConcurrentHashMap<>();
	// Cache RootNode to avoid repeated decompiler.getRoot() calls
	private RootNode cachedRootNode;
	// KeyEventDispatcher for Ctrl+F - stored to remove on dialog close
	private KeyEventDispatcher ctrlFDispatcher;

	public MagicStringsGui(JadxPluginContext pluginContext) {
		this.pluginContext = pluginContext;
	}

	public void init(JadxGuiContext guiContext) {
		guiContext.addMenuAction("Magic Strings: Show Results", () -> {
			guiContext.uiRun(() -> showResults(guiContext));
		});
	}

	private void showResults(JadxGuiContext guiContext) {
		JadxDecompiler decompiler = pluginContext.getDecompiler();
		RootNode root = decompiler.getRoot();
		if (root == null) {
			return;
		}
		// Cache root node for later use (lightweight, no decompiler needed)
		cachedRootNode = root;

		MagicStringsData data = MagicStringsData.getData(root);
		if (data == null) {
			return;
		}

		JDialog dialog = new JDialog(guiContext.getMainFrame(), "Magic Strings Results", false);
		dialog.setSize(1000, 700);
		dialog.setLocationRelativeTo(guiContext.getMainFrame());
		// Request focus when dialog becomes visible to ensure it can capture keyboard events
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent e) {
				dialog.requestFocus();
			}
		});

		// Create search panel
		JPanel searchPanel = createSearchPanel(dialog);
		JTextField searchField = (JTextField) searchPanel.getClientProperty("searchField");

		JTabbedPane tabbedPane = new JTabbedPane();

		// Add listener to reapply search when switching tabs
		tabbedPane.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				if (searchField != null) {
					String searchText = searchField.getText();
					if (searchText != null && !searchText.trim().isEmpty()) {
						filterCurrentTable(dialog, searchText);
					}
				}
			}
		});

		// Source files tab
		if (!data.getSourceFiles().isEmpty()) {
			tabbedPane.addTab("Source Files", createSourceFilesPanel(data, guiContext));
		}

		// Combined tab: Highest score candidates with source files
		if (!data.getMethodCandidates().isEmpty() && !data.getSourceFiles().isEmpty()) {
			tabbedPane.addTab("Top Candidates + Source Files", createTopCandidatesWithSourceFilesPanel(data, guiContext, searchField));
		}

		// Method candidates tab (show filtered candidates)
		if (!data.getFilteredCandidates().isEmpty()) {
			tabbedPane.addTab("Method Candidates (Filtered)", createMethodCandidatesPanel(data, guiContext, searchField));
		} else if (!data.getMethodCandidates().isEmpty()) {
			tabbedPane.addTab("Method Candidates (All)", createMethodCandidatesPanelAll(data, guiContext, searchField));
		}

		// All strings tab
		if (!data.getAllStrings().isEmpty()) {
			tabbedPane.addTab("All Strings", createAllStringsPanel(data));
		}

		if (tabbedPane.getTabCount() == 0) {
			JPanel emptyPanel = new JPanel();
			emptyPanel.add(new JLabel("No magic strings found. Try decompiling the code first."));
			tabbedPane.addTab("No Data", emptyPanel);
		}

		// Add search panel and tabbed pane to dialog
		JPanel mainPanel = new JPanel(new BorderLayout());
		mainPanel.add(searchPanel, BorderLayout.NORTH);
		mainPanel.add(tabbedPane, BorderLayout.CENTER);

		dialog.add(mainPanel);

		// Register Ctrl+F AFTER dialog is set up but BEFORE making it visible
		// This ensures all components are in place
		registerCtrlFShortcut(dialog, searchField);

		// Add window listener to clean up KeyEventDispatcher when dialog closes
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				unregisterCtrlFShortcut();
			}
		});

		dialog.setVisible(true);
	}

	private void registerCtrlFShortcut(JDialog dialog, JTextField searchField) {
		// Remove any existing dispatcher first
		unregisterCtrlFShortcut();

		ctrlFDispatcher = new KeyEventDispatcher() {
			@Override
			public boolean dispatchKeyEvent(KeyEvent e) {
				// Only handle Ctrl+F key press events
				if (e.getID() == KeyEvent.KEY_PRESSED
						&& e.getKeyCode() == KeyEvent.VK_F
						&& (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) {

					// Check if dialog is visible
					if (dialog.isVisible()) {
						// Get the active window - if it's our dialog, handle Ctrl+F
						Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
						boolean isDialogWindow = (activeWindow == dialog);

						// Also check if focus is within the dialog
						Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
						boolean focusInDialog = focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, dialog);

						// If dialog is the active window or focus is in dialog, handle Ctrl+F
						if (isDialogWindow || focusInDialog) {
							// Handle Ctrl+F: focus search field
							if (searchField != null) {
								SwingUtilities.invokeLater(() -> {
									searchField.requestFocus();
									searchField.selectAll();
								});
							}
							// Consume the event to prevent main window from handling it
							e.consume();
							return true;
						}
					}
				}
				return false;
			}
		};

		// Register the dispatcher - this will be called before other handlers
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(ctrlFDispatcher);

		// Also register on dialog's root pane as a backup
		KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK);
		dialog.getRootPane().registerKeyboardAction(
				e -> {
					if (searchField != null && dialog.isVisible()) {
						searchField.requestFocus();
						searchField.selectAll();
					}
				},
				ctrlF,
				JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	private void unregisterCtrlFShortcut() {
		if (ctrlFDispatcher != null) {
			KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(ctrlFDispatcher);
			ctrlFDispatcher = null;
		}
	}

	private JPanel createSearchPanel(JDialog dialog) {
		JPanel searchPanel = new JPanel(new BorderLayout());
		// Add visible border to make search panel more obvious
		searchPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Search"),
				BorderFactory.createEmptyBorder(5, 5, 5, 5)));

		JLabel searchLabel = new JLabel("Search: ");
		JTextField searchField = new JTextField(30);
		searchField.setToolTipText("Search in current table (Ctrl+F to focus)");
		// Make search field more visible with a border
		searchField.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Color.GRAY, 1),
				BorderFactory.createEmptyBorder(2, 5, 2, 5)));

		JPanel searchInputPanel = new JPanel(new BorderLayout(5, 0));
		searchInputPanel.add(searchLabel, BorderLayout.WEST);
		searchInputPanel.add(searchField, BorderLayout.CENTER);

		searchPanel.add(searchInputPanel, BorderLayout.CENTER);

		// Add search listener to filter tables
		searchField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				filterCurrentTable(dialog, searchField.getText());
			}
		});

		// Store search field reference for tab change listener
		searchPanel.putClientProperty("searchField", searchField);

		return searchPanel;
	}

	private void filterCurrentTable(JDialog dialog, String searchText) {
		JTabbedPane tabbedPane = findTabbedPane(dialog);
		if (tabbedPane == null) {
			return;
		}

		Component selectedComponent = tabbedPane.getSelectedComponent();
		if (selectedComponent instanceof JPanel) {
			JPanel panel = (JPanel) selectedComponent;
			JScrollPane scrollPane = findScrollPane(panel);
			if (scrollPane != null) {
				Component viewportView = scrollPane.getViewport().getView();
				if (viewportView instanceof JTable) {
					JTable table = (JTable) viewportView;
					applyTableFilter(table, searchText);
				} else if (viewportView instanceof JTree) {
					JTree tree = (JTree) viewportView;
					applyTreeFilter(tree, searchText);
				}
			}
		}
	}

	private void applyTableFilter(JTable table, String searchText) {
		if (table.getRowSorter() == null) {
			// Create row sorter if it doesn't exist
			TableRowSorter<?> sorter = new TableRowSorter<>(table.getModel());
			table.setRowSorter(sorter);
		}

		TableRowSorter<?> sorter = (TableRowSorter<?>) table.getRowSorter();
		if (searchText == null || searchText.trim().isEmpty()) {
			sorter.setRowFilter(null);
		} else {
			try {
				// Case-insensitive search across all columns
				String searchPattern = "(?i)" + Pattern.quote(searchText.trim());
				sorter.setRowFilter(RowFilter.regexFilter(searchPattern));
			} catch (PatternSyntaxException e) {
				// Invalid regex, ignore
				LOG.debug("Invalid search pattern: {}", searchText, e);
			}
		}
	}

	private void applyTreeFilter(JTree tree, String searchText) {
		// Get stored original data - could be source files or top candidates
		Object originalDataObj = tree.getClientProperty("originalData");
		
		if (originalDataObj == null) {
			return; // Can't filter without original data
		}
		
		String searchLower = (searchText == null || searchText.trim().isEmpty()) ? null : searchText.trim().toLowerCase();
		
		// Check if it's source files data or top candidates data
		if (originalDataObj instanceof Map && 
				!((Map<?, ?>) originalDataObj).isEmpty() &&
				((Map<?, ?>) originalDataObj).values().iterator().next() instanceof List) {
			@SuppressWarnings("unchecked")
			Map<String, List<MagicStringsData.SourceFileReference>> sourceFileData = 
				(Map<String, List<MagicStringsData.SourceFileReference>>) originalDataObj;
			applySourceFileTreeFilter(tree, sourceFileData, searchLower);
		} else if (originalDataObj instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, List<TopCandidateWithSourceFile>> topCandidatesData = 
				(Map<String, List<TopCandidateWithSourceFile>>) originalDataObj;
			applyTopCandidatesTreeFilter(tree, topCandidatesData, searchLower);
		}
	}

	private void applySourceFileTreeFilter(JTree tree, Map<String, List<MagicStringsData.SourceFileReference>> originalData, String searchLower) {
		// Rebuild tree with filtered data
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Source Files");
		
		for (Map.Entry<String, List<MagicStringsData.SourceFileReference>> entry : originalData.entrySet()) {
			String filePath = entry.getKey();
			List<MagicStringsData.SourceFileReference> refs = entry.getValue();
			
			// Filter references
			List<MagicStringsData.SourceFileReference> filteredRefs = new ArrayList<>();
			if (searchLower == null) {
				filteredRefs.addAll(refs);
			} else {
				// Check if file path matches
				boolean fileMatches = filePath.toLowerCase().contains(searchLower);
				
				// Check each reference
				for (MagicStringsData.SourceFileReference ref : refs) {
					boolean refMatches = fileMatches
							|| ref.getMethodName().toLowerCase().contains(searchLower)
							|| ref.getMethodRef().toLowerCase().contains(searchLower)
							|| ref.getStringData().toLowerCase().contains(searchLower);
					if (refMatches) {
						filteredRefs.add(ref);
					}
				}
			}
			
			// Only add file node if it has matching references or file path matches (or no search)
			if (searchLower == null || !filteredRefs.isEmpty() || filePath.toLowerCase().contains(searchLower)) {
				SourceFileTreeNodeData fileData = new SourceFileTreeNodeData(filePath, null, filePath);
				DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileData);
				
				for (MagicStringsData.SourceFileReference ref : filteredRefs) {
					String nodeText = String.format("%s [%s] - %s",
							ref.getMethodName(), ref.getMethodRef(), ref.getStringData());
					SourceFileTreeNodeData refData = new SourceFileTreeNodeData(filePath, ref.getMethodRef(), nodeText);
					DefaultMutableTreeNode refNode = new DefaultMutableTreeNode(refData);
					fileNode.add(refNode);
				}
				
				rootNode.add(fileNode);
			}
		}
		
		// Update tree model
		DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
		model.setRoot(rootNode);
		model.reload();
		
		// Expand all nodes after filtering
		expandAllTreeNodes(tree, new TreePath(rootNode));
	}

	private void applyTopCandidatesTreeFilter(JTree tree, Map<String, List<TopCandidateWithSourceFile>> originalData, String searchLower) {
		// Use cached root node
		RootNode root = cachedRootNode;
		if (root == null) {
			JadxDecompiler decompiler = pluginContext.getDecompiler();
			if (decompiler != null) {
				RootNode newRoot = decompiler.getRoot();
				if (newRoot != null) {
					cachedRootNode = newRoot;
					root = newRoot;
				}
			}
		}
		final RootNode finalRoot = root;
		
		// Rebuild tree with filtered data
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Top Candidates by Source File");
		
		for (Map.Entry<String, List<TopCandidateWithSourceFile>> entry : originalData.entrySet()) {
			String sourceFile = entry.getKey();
			List<TopCandidateWithSourceFile> fileCandidates = entry.getValue();
			
			// Filter candidates
			List<TopCandidateWithSourceFile> filteredCandidates = new ArrayList<>();
			if (searchLower == null) {
				filteredCandidates.addAll(fileCandidates);
			} else {
				// Check if file path matches
				boolean fileMatches = sourceFile.toLowerCase().contains(searchLower);
				
				// Check each candidate
				for (TopCandidateWithSourceFile candidate : fileCandidates) {
					boolean candidateMatches = fileMatches
							|| candidate.getMethodRef().toLowerCase().contains(searchLower)
							|| candidate.getCandidate().toLowerCase().contains(searchLower)
							|| candidate.getStringData().toLowerCase().contains(searchLower);
					if (candidateMatches) {
						filteredCandidates.add(candidate);
					}
				}
			}
			
			// Only add file node if it has matching candidates or file path matches (or no search)
			if (searchLower == null || !filteredCandidates.isEmpty() || sourceFile.toLowerCase().contains(searchLower)) {
				TopCandidateTreeNodeData fileData = new TopCandidateTreeNodeData(sourceFile, null, null, 0, sourceFile);
				DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileData);
				
				for (TopCandidateWithSourceFile candidate : filteredCandidates) {
					// Get current method name (with caching)
					String currentName = methodNameCache.computeIfAbsent(candidate.getMethodRef(), ref -> {
						try {
							MethodNode methodNode = findMethodNodeByRef(finalRoot, ref);
							if (methodNode != null) {
								return methodNode.getName();
							}
						} catch (Exception e) {
							// Ignore
						}
						return "?";
					});
					
					// Format: "methodName -> candidateName (score)"
					String displayText = String.format("%s -> %s (score: %d)", 
							currentName, candidate.getCandidate(), candidate.getScore());
					
					TopCandidateTreeNodeData methodData = new TopCandidateTreeNodeData(
							sourceFile, candidate.getMethodRef(), candidate.getCandidate(), 
							currentName, candidate.getScore(), displayText);
					DefaultMutableTreeNode methodNode = new DefaultMutableTreeNode(methodData);
					fileNode.add(methodNode);
				}
				
				rootNode.add(fileNode);
			}
		}
		
		// Update tree model
		DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
		model.setRoot(rootNode);
		model.reload();
		
		// Expand all nodes after filtering
		expandAllTreeNodes(tree, new TreePath(rootNode));
	}

	private void expandAllTreeNodes(JTree tree, TreePath parent) {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getLastPathComponent();
		if (node.getChildCount() >= 0) {
			for (int i = 0; i < node.getChildCount(); i++) {
				DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
				TreePath path = parent.pathByAddingChild(child);
				expandAllTreeNodes(tree, path);
			}
		}
		tree.expandPath(parent);
	}

	private static class SourceFileTreeNodeData {
		private final String filePath;
		private final String methodRef;
		private final String displayText;

		SourceFileTreeNodeData(String filePath, String methodRef, String displayText) {
			this.filePath = filePath;
			this.methodRef = methodRef;
			this.displayText = displayText;
		}

		String getFilePath() {
			return filePath;
		}

		String getMethodRef() {
			return methodRef;
		}

		@Override
		public String toString() {
			return displayText;
		}
	}

	private static class TopCandidateTreeNodeData {
		private final String methodRef;
		private final String candidate;
		private final String currentName;
		private final int score;
		private final String sourceFile;
		private final String displayText;

		TopCandidateTreeNodeData(String sourceFile, String methodRef, String candidate, 
				String currentName, int score, String displayText) {
			this.sourceFile = sourceFile;
			this.methodRef = methodRef;
			this.candidate = candidate;
			this.currentName = currentName;
			this.score = score;
			this.displayText = displayText;
		}

		TopCandidateTreeNodeData(String sourceFile, String methodRef, String candidate, int score, String displayText) {
			this(sourceFile, methodRef, candidate, null, score, displayText);
		}

		String getMethodRef() {
			return methodRef;
		}

		String getCandidate() {
			return candidate;
		}

		String getCurrentName() {
			return currentName;
		}

		int getScore() {
			return score;
		}

		String getSourceFile() {
			return sourceFile;
		}

		@Override
		public String toString() {
			return displayText;
		}
	}

	private JTabbedPane findTabbedPane(JDialog dialog) {
		return findComponent(dialog, JTabbedPane.class);
	}

	private JScrollPane findScrollPane(JPanel panel) {
		return findComponent(panel, JScrollPane.class);
	}

	@SuppressWarnings("unchecked")
	private <T extends Component> T findComponent(Container container, Class<T> type) {
		for (Component comp : container.getComponents()) {
			if (type.isInstance(comp)) {
				return (T) comp;
			}
			if (comp instanceof Container) {
				T found = findComponent((Container) comp, type);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private JPanel createSourceFilesPanel(MagicStringsData data, JadxGuiContext guiContext) {
		JPanel panel = new JPanel(new BorderLayout());

		// Create tree
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Source Files");
		DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
		JTree tree = new JTree(treeModel);

		// Store original data for filtering
		tree.putClientProperty("originalData", data.getSourceFiles());

		// Build tree with SourceFileTreeNodeData
		for (Map.Entry<String, List<MagicStringsData.SourceFileReference>> entry : data.getSourceFiles().entrySet()) {
			String filePath = entry.getKey();
			SourceFileTreeNodeData fileData = new SourceFileTreeNodeData(filePath, null, filePath);
			DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileData);

			for (MagicStringsData.SourceFileReference ref : entry.getValue()) {
				String nodeText = String.format("%s [%s] - %s",
						ref.getMethodName(), ref.getMethodRef(), ref.getStringData());
				SourceFileTreeNodeData refData = new SourceFileTreeNodeData(filePath, ref.getMethodRef(), nodeText);
				DefaultMutableTreeNode refNode = new DefaultMutableTreeNode(refData);
				fileNode.add(refNode);
			}

			rootNode.add(fileNode);
		}

		treeModel.reload();

		// Custom cell renderer - only file paths are bold, child rows are normal
		tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
			@Override
			public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
					boolean leaf, int row, boolean hasFocus) {
				Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
				
				if (c instanceof JLabel) {
					JLabel label = (JLabel) c;
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
					Object userObject = node.getUserObject();
					
					if (userObject instanceof SourceFileTreeNodeData) {
						SourceFileTreeNodeData nodeData = (SourceFileTreeNodeData) userObject;
						if (nodeData.getMethodRef() == null) {
							// File path node - bold only, use a subtle darker blue
							Font font = label.getFont();
							if (font != null) {
								label.setFont(new Font(font.getName(), Font.BOLD, font.getSize()));
							}
							// Use a darker, more subtle blue for better readability
							label.setForeground(new Color(0, 70, 150));
							label.setToolTipText("Click to navigate to class: " + nodeData.getFilePath());
						} else {
							// Method reference node - normal weight, default text color
							Font font = label.getFont();
							if (font != null) {
								label.setFont(new Font(font.getName(), Font.PLAIN, font.getSize()));
							}
							// Use default text color for child rows (more readable)
							// The super class already sets the foreground based on selection state
							label.setToolTipText("Click to navigate to method: " + nodeData.getMethodRef());
						}
					}
				}
				
				return c;
			}
		});

		// Add mouse listener for navigation
		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 1 || e.getClickCount() == 2) {
					TreePath path = tree.getPathForLocation(e.getX(), e.getY());
					if (path != null) {
						DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
						Object userObject = node.getUserObject();
						
						if (userObject instanceof SourceFileTreeNodeData) {
							SourceFileTreeNodeData nodeData = (SourceFileTreeNodeData) userObject;
							
							if (nodeData.getMethodRef() == null) {
								// File path clicked - navigate to class
								navigateToClassFromFilePath(guiContext, nodeData.getFilePath());
							} else {
								// Method reference clicked - navigate to method
								navigateToMethod(guiContext, null, nodeData.getMethodRef());
							}
						}
					}
				}
			}
		});

		// Add mouse motion listener for cursor changes
		tree.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (path != null) {
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
					Object userObject = node.getUserObject();
					if (userObject instanceof SourceFileTreeNodeData) {
						tree.setCursor(new Cursor(Cursor.HAND_CURSOR));
					} else {
						tree.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
					}
				} else {
					tree.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
				}
			}
		});

		// Keyboard navigation - Enter key
		tree.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					TreePath path = tree.getSelectionPath();
					if (path != null) {
						DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
						Object userObject = node.getUserObject();
						
						if (userObject instanceof SourceFileTreeNodeData) {
							SourceFileTreeNodeData nodeData = (SourceFileTreeNodeData) userObject;
							
							if (nodeData.getMethodRef() == null) {
								// File path - navigate to class
								navigateToClassFromFilePath(guiContext, nodeData.getFilePath());
							} else {
								// Method reference - navigate to method
								navigateToMethod(guiContext, null, nodeData.getMethodRef());
							}
						}
					}
				}
			}
		});

		// Expand all nodes initially
		expandAllTreeNodes(tree, new TreePath(rootNode));

		JScrollPane scrollPane = new JScrollPane(tree);
		scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(scrollPane, BorderLayout.CENTER);

		JLabel infoLabel = new JLabel(String.format("Found %d source files", data.getSourceFiles().size()));
		infoLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(infoLabel, BorderLayout.SOUTH);

		return panel;
	}

	private JPanel createTopCandidatesWithSourceFilesPanel(MagicStringsData data, JadxGuiContext guiContext, JTextField searchField) {
		JPanel panel = new JPanel(new BorderLayout());

		// Use cached root node instead of decompiler (lightweight)
		RootNode root = cachedRootNode;
		if (root == null) {
			root = pluginContext.getDecompiler().getRoot();
			cachedRootNode = root;
		}

		// Build a map from methodRef to source file info
		Map<String, String> methodToSourceFile = new HashMap<>();
		Map<String, String> methodToStringData = new HashMap<>();
		for (Map.Entry<String, List<MagicStringsData.SourceFileReference>> entry : data.getSourceFiles().entrySet()) {
			String filePath = entry.getKey();
			for (MagicStringsData.SourceFileReference ref : entry.getValue()) {
				methodToSourceFile.put(ref.getMethodRef(), filePath);
				// Use the first string data found for this method
				if (!methodToStringData.containsKey(ref.getMethodRef())) {
					methodToStringData.put(ref.getMethodRef(), ref.getStringData());
				}
			}
		}

		// Get highest scoring candidate for each method
		List<TopCandidateWithSourceFile> topCandidates = new ArrayList<>();
		Map<String, Map<String, Integer>> candidateScores = data.getCandidateScores();
		Map<String, Set<String>> methodCandidates = data.getMethodCandidates();
		Map<String, Set<String>> methodRawStrings = data.getMethodRawStrings();
		Map<String, Set<String>> candidateRarity = data.getCandidateRarity();

		for (Map.Entry<String, Set<String>> entry : methodCandidates.entrySet()) {
			String methodRef = entry.getKey();
			Set<String> candidates = entry.getValue();
			Map<String, Integer> scores = candidateScores.getOrDefault(methodRef, new HashMap<>());

			// Find highest scoring candidate
			String topCandidate = null;
			int topScore = Integer.MIN_VALUE;
			int topRarity = 0;

			for (String candidate : candidates) {
				int score = scores.getOrDefault(candidate, 0);
				Set<String> methodsUsingCandidate = candidateRarity.get(candidate);
				int rarity = methodsUsingCandidate != null ? methodsUsingCandidate.size() : 0;

				// Prioritize: rarity == 1 first, then highest score
				boolean isBetter = false;
				if (topCandidate == null) {
					isBetter = true;
				} else if (rarity == 1 && topRarity != 1) {
					isBetter = true;
				} else if (rarity != 1 && topRarity == 1) {
					isBetter = false;
				} else if (score > topScore) {
					isBetter = true;
				}

				if (isBetter) {
					topCandidate = candidate;
					topScore = score;
					topRarity = rarity;
				}
			}

			if (topCandidate != null) {
				String sourceFile = methodToSourceFile.getOrDefault(methodRef, "");
				String stringData = methodToStringData.getOrDefault(methodRef, "");
				Set<String> rawStrings = methodRawStrings.getOrDefault(methodRef, Set.of());
				topCandidates.add(new TopCandidateWithSourceFile(methodRef, topCandidate, topScore, topRarity, 
						sourceFile, stringData, rawStrings));
			}
		}

		// Sort by score descending
		topCandidates.sort((a, b) -> {
			// Prioritize rarity == 1
			if (a.rarity == 1 && b.rarity != 1) {
				return -1;
			}
			if (a.rarity != 1 && b.rarity == 1) {
				return 1;
			}
			// Then by score
			return Integer.compare(b.score, a.score);
		});

		// Group candidates by source file
		Map<String, List<TopCandidateWithSourceFile>> candidatesByFile = new HashMap<>();
		for (TopCandidateWithSourceFile candidate : topCandidates) {
			String sourceFile = candidate.getSourceFile();
			if (sourceFile == null || sourceFile.isEmpty()) {
				sourceFile = "(No source file)";
			}
			candidatesByFile.computeIfAbsent(sourceFile, k -> new ArrayList<>()).add(candidate);
		}

		// Create tree
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Top Candidates by Source File");
		DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
		JTree tree = new JTree(treeModel);

		// Store original data for filtering
		tree.putClientProperty("originalData", candidatesByFile);

		// Build tree structure: Source File -> Methods with candidates
		for (Map.Entry<String, List<TopCandidateWithSourceFile>> entry : candidatesByFile.entrySet()) {
			String sourceFile = entry.getKey();
			List<TopCandidateWithSourceFile> fileCandidates = entry.getValue();

			// Create source file node
			TopCandidateTreeNodeData fileData = new TopCandidateTreeNodeData(sourceFile, null, null, 0, sourceFile);
			DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileData);

			// Add method nodes as children
			final RootNode finalRootForTree = root;
			for (TopCandidateWithSourceFile candidate : fileCandidates) {
				// Get current method name (with caching)
				String currentName = methodNameCache.computeIfAbsent(candidate.getMethodRef(), ref -> {
					try {
						MethodNode methodNode = findMethodNodeByRef(finalRootForTree, ref);
						if (methodNode != null) {
							return methodNode.getName();
						}
					} catch (Exception e) {
						// Ignore
					}
					return "?";
				});

				// Format: "methodName -> candidateName (score)"
				String displayText = String.format("%s -> %s (score: %d)", 
						currentName, candidate.getCandidate(), candidate.getScore());
				
				TopCandidateTreeNodeData methodData = new TopCandidateTreeNodeData(
						sourceFile, candidate.getMethodRef(), candidate.getCandidate(), 
						currentName, candidate.getScore(), displayText);
				DefaultMutableTreeNode methodNode = new DefaultMutableTreeNode(methodData);
				fileNode.add(methodNode);
			}

			rootNode.add(fileNode);
		}

		treeModel.reload();

		// Custom cell renderer
		tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
			@Override
			public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
					boolean leaf, int row, boolean hasFocus) {
				Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

				if (c instanceof JLabel) {
					JLabel label = (JLabel) c;
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
					Object userObject = node.getUserObject();

					if (userObject instanceof TopCandidateTreeNodeData) {
						TopCandidateTreeNodeData nodeData = (TopCandidateTreeNodeData) userObject;
						if (nodeData.getMethodRef() == null) {
							// Source file node - bold, darker blue
							Font font = label.getFont();
							if (font != null) {
								label.setFont(new Font(font.getName(), Font.BOLD, font.getSize()));
							}
							label.setForeground(new Color(0, 70, 150));
							label.setToolTipText("Click to navigate to class: " + nodeData.getSourceFile());
						} else {
							// Method node - normal weight, default color
							Font font = label.getFont();
							if (font != null) {
								label.setFont(new Font(font.getName(), Font.PLAIN, font.getSize()));
							}
							label.setToolTipText("Click to navigate to method: " + nodeData.getMethodRef());
						}
					}
				}

				return c;
			}
		});

		// Add mouse listener for navigation and context menu
		JPopupMenu popupMenu = new JPopupMenu();
		JMenuItem renameItem = new JMenuItem("Rename method to candidate");
		renameItem.addActionListener(e -> {
			TreePath path = tree.getSelectionPath();
			if (path != null) {
				DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
				Object userObject = node.getUserObject();
				if (userObject instanceof TopCandidateTreeNodeData) {
					TopCandidateTreeNodeData nodeData = (TopCandidateTreeNodeData) userObject;
					if (nodeData.getMethodRef() != null && nodeData.getCandidate() != null) {
						JadxDecompiler decompiler = pluginContext.getDecompiler();
						renameMethod(guiContext, decompiler, nodeData.getMethodRef(), nodeData.getCandidate());
					}
				}
			}
		});
		popupMenu.add(renameItem);

		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 1 || e.getClickCount() == 2) {
					TreePath path = tree.getPathForLocation(e.getX(), e.getY());
					if (path != null) {
						DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
						Object userObject = node.getUserObject();

						if (userObject instanceof TopCandidateTreeNodeData) {
							TopCandidateTreeNodeData nodeData = (TopCandidateTreeNodeData) userObject;

							if (nodeData.getMethodRef() == null) {
								// Source file clicked - navigate to class
								if (!nodeData.getSourceFile().equals("(No source file)")) {
									navigateToClassFromFilePath(guiContext, nodeData.getSourceFile());
								}
							} else {
								// Method clicked - navigate to method
								navigateToMethod(guiContext, null, nodeData.getMethodRef());
							}
						}
					}
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				if (e.isPopupTrigger()) {
					showPopup(e);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (e.isPopupTrigger()) {
					showPopup(e);
				}
			}

			private void showPopup(MouseEvent e) {
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (path != null) {
					tree.setSelectionPath(path);
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
					Object userObject = node.getUserObject();
					if (userObject instanceof TopCandidateTreeNodeData) {
						TopCandidateTreeNodeData nodeData = (TopCandidateTreeNodeData) userObject;
						// Only show rename menu for method nodes
						if (nodeData.getMethodRef() != null) {
							popupMenu.show(tree, e.getX(), e.getY());
						}
					}
				}
			}
		});

		// Mouse motion listener for cursor changes
		tree.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (path != null) {
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
					Object userObject = node.getUserObject();
					if (userObject instanceof TopCandidateTreeNodeData) {
						tree.setCursor(new Cursor(Cursor.HAND_CURSOR));
					} else {
						tree.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
					}
				} else {
					tree.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
				}
			}
		});

		// Keyboard navigation - Enter key
		tree.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					TreePath path = tree.getSelectionPath();
					if (path != null) {
						DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
						Object userObject = node.getUserObject();

						if (userObject instanceof TopCandidateTreeNodeData) {
							TopCandidateTreeNodeData nodeData = (TopCandidateTreeNodeData) userObject;

							if (nodeData.getMethodRef() == null) {
								// Source file - navigate to class
								if (!nodeData.getSourceFile().equals("(No source file)")) {
									navigateToClassFromFilePath(guiContext, nodeData.getSourceFile());
								}
							} else {
								// Method - navigate to method
								navigateToMethod(guiContext, null, nodeData.getMethodRef());
							}
						}
					}
				}
			}
		});

		// Expand all nodes initially
		expandAllTreeNodes(tree, new TreePath(rootNode));

		JScrollPane scrollPane = new JScrollPane(tree);
		scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(scrollPane, BorderLayout.CENTER);

		JLabel infoLabel = new JLabel(String.format("Found %d top candidates in %d source files", 
				topCandidates.size(), candidatesByFile.size()));
		infoLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(infoLabel, BorderLayout.SOUTH);

		return panel;
	}

	private static class TopCandidateWithSourceFile {
		private final String methodRef;
		private final String candidate;
		private final int score;
		private final int rarity;
		private final String sourceFile;
		private final String stringData;
		private final Set<String> rawStrings;

		TopCandidateWithSourceFile(String methodRef, String candidate, int score, int rarity,
				String sourceFile, String stringData, Set<String> rawStrings) {
			this.methodRef = methodRef;
			this.candidate = candidate;
			this.score = score;
			this.rarity = rarity;
			this.sourceFile = sourceFile;
			this.stringData = stringData;
			this.rawStrings = rawStrings;
		}

		String getMethodRef() { return methodRef; }
		String getCandidate() { return candidate; }
		int getScore() { return score; }
		int getRarity() { return rarity; }
		String getSourceFile() { return sourceFile; }
		String getStringData() { return stringData; }
		Set<String> getRawStrings() { return rawStrings; }
	}

	private JPanel createMethodCandidatesPanel(MagicStringsData data, JadxGuiContext guiContext, JTextField searchField) {
		JPanel panel = new JPanel(new BorderLayout());

		// Use cached root node instead of decompiler (lightweight)
		RootNode root = cachedRootNode;
		if (root == null) {
			root = pluginContext.getDecompiler().getRoot();
			cachedRootNode = root;
		}

		List<MagicStringsData.MethodCandidate> candidates = data.getFilteredCandidates();

		// Create lazy-loading table model (pass root instead of decompiler)
		LazyMethodCandidatesTableModel tableModel = new LazyMethodCandidatesTableModel(candidates, root,
				methodNameCache);

		JTable table = new JTable(tableModel) {
			@Override
			public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
				java.awt.Component c = super.prepareRenderer(renderer, row, column);

				// Make clickable columns (Method ref and Candidate name) look clickable
				if (column == 0 || column == 2) {
					// Method reference or Candidate name - make it look clickable
					if (c instanceof javax.swing.JLabel) {
						javax.swing.JLabel label = (javax.swing.JLabel) c;
						Font font = label.getFont();
						if (font != null) {
							label.setFont(new Font(font.getName(), Font.BOLD, font.getSize()));
						}
						label.setForeground(new Color(0, 0, 238)); // Link blue color
						if (c instanceof javax.swing.JComponent) {
							((javax.swing.JComponent) c).setToolTipText("Click to navigate to method");
						}
					}
				}

				// Color false positive column
				if (column == 3) {
					Object value = getValueAt(row, column);
					if ("Yes".equals(value)) {
						c.setBackground(new Color(255, 200, 200)); // Light red
					} else {
						c.setBackground(Color.WHITE);
					}
				} else if (column != 0 && column != 2) {
					// Don't override background for clickable columns
					c.setBackground(Color.WHITE);
				}

				// Add tooltip for raw strings column
				if (column == 4 && c instanceof javax.swing.JComponent) {
					Object value = getValueAt(row, column);
					if (value != null) {
						String str = value.toString();
						// Get full string from row data if available
						String fullString = tableModel.getFullRawString(row);
						if (fullString != null && fullString.length() > str.length()) {
							((javax.swing.JComponent) c).setToolTipText(fullString);
						} else {
							((javax.swing.JComponent) c).setToolTipText(str);
						}
					}
				}

				return c;
			}
		};

		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setRowHeight(20); // Fixed row height for better performance
		table.getColumnModel().getColumn(0).setPreferredWidth(300);
		table.getColumnModel().getColumn(1).setPreferredWidth(150);
		table.getColumnModel().getColumn(2).setPreferredWidth(200);
		table.getColumnModel().getColumn(3).setPreferredWidth(100);
		table.getColumnModel().getColumn(4).setPreferredWidth(400);

		// Add context menu for renaming
		JPopupMenu popupMenu = new JPopupMenu();
		JMenuItem renameItem = new JMenuItem("Rename method to candidate");
		renameItem.addActionListener(e -> {
			int selectedRow = table.getSelectedRow();
			if (selectedRow >= 0) {
				String methodRef = (String) tableModel.getValueAt(selectedRow, 0);
				String candidateName = (String) tableModel.getValueAt(selectedRow, 2);
				// Get decompiler only when needed for renaming (requires ICodeData)
				JadxDecompiler decompiler = pluginContext.getDecompiler();
				renameMethod(guiContext, decompiler, methodRef, candidateName);
			}
		});
		popupMenu.add(renameItem);

		JMenuItem renameAllItem = new JMenuItem("Rename all methods to candidates");
		renameAllItem.addActionListener(e -> {
			// Get decompiler only when needed for renaming (requires ICodeData)
			JadxDecompiler decompiler = pluginContext.getDecompiler();
			int count = renameAllMethods(guiContext, decompiler, candidates);
			JOptionPane.showMessageDialog(panel, "Renamed " + count + " methods", "Rename Complete",
					JOptionPane.INFORMATION_MESSAGE);
		});
		popupMenu.add(renameAllItem);

		// Mouse listener for navigation and context menu
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (e.isPopupTrigger()) {
					showPopup(e);
				} else if (e.getClickCount() == 1) {
					// Single click on method reference or candidate name to navigate
					handleTableClick(e);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (e.isPopupTrigger()) {
					showPopup(e);
				}
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				// Double click also navigates
				if (e.getClickCount() == 2) {
					handleTableClick(e);
				}
			}

			private void showPopup(MouseEvent e) {
				int row = table.rowAtPoint(e.getPoint());
				if (row >= 0) {
					table.setRowSelectionInterval(row, row);
					popupMenu.show(table, e.getX(), e.getY());
				}
			}

			private void handleTableClick(MouseEvent e) {
				int row = table.rowAtPoint(e.getPoint());
				int col = table.columnAtPoint(e.getPoint());
				if (row >= 0 && (col == 0 || col == 2)) {
					// Column 0 = Method reference, Column 2 = Candidate name
					String methodRef = (String) tableModel.getValueAt(row, 0);
					// Pass null for decompiler - we'll use cached root node (lightweight)
					navigateToMethod(guiContext, null, methodRef);
				}
			}
		});

		// Mouse motion listener for cursor changes
		table.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int row = table.rowAtPoint(e.getPoint());
				int col = table.columnAtPoint(e.getPoint());
				if (row >= 0 && (col == 0 || col == 2)) {
					table.setCursor(new Cursor(Cursor.HAND_CURSOR));
				} else {
					table.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
				}
			}
		});

		// Keyboard navigation - Enter key to navigate
		table.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					int selectedRow = table.getSelectedRow();
					int selectedCol = table.getSelectedColumn();
					if (selectedRow >= 0 && (selectedCol == 0 || selectedCol == 2)) {
						String methodRef = (String) tableModel.getValueAt(selectedRow, 0);
						// Pass null for decompiler - we'll use cached root node
						navigateToMethod(guiContext, null, methodRef);
					}
				}
			}
		});

		// Ctrl+F is handled globally via KeyEventDispatcher, no need to register on table

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(scrollPane, BorderLayout.CENTER);

		// Progress bar and status label
		JPanel statusPanel = new JPanel(new BorderLayout());
		JProgressBar progressBar = new JProgressBar(0, candidates.size());
		progressBar.setStringPainted(true);
		progressBar.setString("Loading candidates...");
		statusPanel.add(progressBar, BorderLayout.CENTER);

		JLabel infoLabel = new JLabel(String.format("Found %d filtered method candidates", candidates.size()));
		infoLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		statusPanel.add(infoLabel, BorderLayout.SOUTH);

		panel.add(statusPanel, BorderLayout.SOUTH);

		// Start background loading
		tableModel.startBackgroundLoading(progressBar, infoLabel);

		return panel;
	}

	private JPanel createMethodCandidatesPanelAll(MagicStringsData data, JadxGuiContext guiContext, JTextField searchField) {
		JPanel panel = new JPanel(new BorderLayout());

		String[] columnNames = { "Method", "Candidate Name", "Raw Strings" };
		DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};

		for (Map.Entry<String, Set<String>> entry : data.getMethodCandidates().entrySet()) {
			String methodRef = entry.getKey();
			Set<String> candidates = entry.getValue();
			Set<String> rawStrings = data.getMethodRawStrings().getOrDefault(methodRef, Set.of());

			for (String candidate : candidates) {
				String rawStr = String.join(", ", rawStrings);
				tableModel.addRow(new Object[] { methodRef, candidate, rawStr });
			}
		}

		JTable table = new JTable(tableModel) {
			@Override
			public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
				java.awt.Component c = super.prepareRenderer(renderer, row, column);

				// Make clickable columns (Method ref and Candidate name) look clickable
				if (column == 0 || column == 1) {
					// Method reference or Candidate name - make it look clickable
					if (c instanceof javax.swing.JLabel) {
						javax.swing.JLabel label = (javax.swing.JLabel) c;
						Font font = label.getFont();
						if (font != null) {
							label.setFont(new Font(font.getName(), Font.BOLD, font.getSize()));
						}
						label.setForeground(new Color(0, 0, 238)); // Link blue color
						if (c instanceof javax.swing.JComponent) {
							((javax.swing.JComponent) c).setToolTipText("Click to navigate to method");
						}
					}
				}

				return c;
			}
		};

		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.getColumnModel().getColumn(0).setPreferredWidth(300);
		table.getColumnModel().getColumn(1).setPreferredWidth(200);
		table.getColumnModel().getColumn(2).setPreferredWidth(400);

		// Mouse listener for navigation
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (e.getClickCount() == 1) {
					handleTableClick(e);
				}
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					handleTableClick(e);
				}
			}

			private void handleTableClick(MouseEvent e) {
				int row = table.rowAtPoint(e.getPoint());
				int col = table.columnAtPoint(e.getPoint());
				if (row >= 0 && (col == 0 || col == 1)) {
					// Column 0 = Method reference, Column 1 = Candidate name
					String methodRef = (String) tableModel.getValueAt(row, 0);
					// Pass null for decompiler - we'll use cached root node (lightweight)
					navigateToMethod(guiContext, null, methodRef);
				}
			}
		});

		// Mouse motion listener for cursor changes
		table.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int row = table.rowAtPoint(e.getPoint());
				int col = table.columnAtPoint(e.getPoint());
				if (row >= 0 && (col == 0 || col == 1)) {
					table.setCursor(new Cursor(Cursor.HAND_CURSOR));
				} else {
					table.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
				}
			}
		});

		// Keyboard navigation - Enter key to navigate
		table.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					int selectedRow = table.getSelectedRow();
					int selectedCol = table.getSelectedColumn();
					if (selectedRow >= 0 && (selectedCol == 0 || selectedCol == 1)) {
						String methodRef = (String) tableModel.getValueAt(selectedRow, 0);
						// Pass null for decompiler - we'll use cached root node
						navigateToMethod(guiContext, null, methodRef);
					}
				}
			}
		});

		// Ctrl+F is handled globally via KeyEventDispatcher, no need to register on table

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(scrollPane, BorderLayout.CENTER);

		JLabel infoLabel = new JLabel(String.format("Found %d method candidates (all, not filtered)",
				data.getMethodCandidates().size()));
		infoLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(infoLabel, BorderLayout.SOUTH);

		return panel;
	}

	private JavaMethod findMethodByRef(JadxDecompiler decompiler, String methodRef) {
		// Method ref format: "com.example.Class.methodName(ArgType1,ArgType2):ReturnType"
		// or "com.example.Class.methodName()" for methods without parameters
		try {
			int lastDot = methodRef.lastIndexOf('.');
			if (lastDot < 0) {
				return null;
			}
			String className = methodRef.substring(0, lastDot);
			String methodSig = methodRef.substring(lastDot + 1);

			// Try original class name first (since that's what we stored)
			jadx.api.JavaClass javaClass = decompiler.searchJavaClassByOrigFullName(className);
			if (javaClass == null) {
				// Fallback to alias name in case class was renamed
				javaClass = decompiler.searchJavaClassByAliasFullName(className);
			}
			if (javaClass == null) {
				return null;
			}

			// Search for method by short ID
			JavaMethod method = javaClass.searchMethodByShortId(methodSig);
			if (method != null) {
				return method;
			}

			// If not found, try searching all methods in the class
			// This handles cases where the signature format might be slightly different
			for (JavaMethod m : javaClass.getMethods()) {
				if (m.getFullName().equals(methodRef)) {
					return m;
				}
			}

			return null;
		} catch (Exception e) {
			// Log error for debugging but don't fail
			org.slf4j.LoggerFactory.getLogger(MagicStringsGui.class)
					.debug("Failed to find method by ref: {}", methodRef, e);
			return null;
		}
	}

	private void renameMethod(JadxGuiContext guiContext, JadxDecompiler decompiler, String methodRef,
			String newName) {
		try {
			// Validate and sanitize the new name
			String sanitizedName = sanitizeMethodName(newName);
			if (sanitizedName == null || sanitizedName.isEmpty()) {
				JOptionPane.showMessageDialog(null,
						"Invalid method name: \"" + newName + "\"\n\n"
								+ "The name must be a valid Java identifier.",
						"Error", JOptionPane.ERROR_MESSAGE);
				LOG.warn("Invalid method name for rename: '{}' (original: '{}')", sanitizedName, newName);
				return;
			}

			// Find the method
			JavaMethod method = findMethodByRef(decompiler, methodRef);
			if (method == null) {
				// Try a more aggressive search as fallback
				method = findMethodByRefFallback(decompiler, methodRef);
				if (method == null) {
					LOG.warn("Method not found for rename: {}", methodRef);
					JOptionPane.showMessageDialog(null,
							"Method not found: " + methodRef + "\n\n"
									+ "This may happen if the method was removed or the class structure changed.",
							"Error", JOptionPane.ERROR_MESSAGE);
					return;
				}
			}

			// Ensure codeData is initialized
			ICodeData codeData = decompiler.getArgs().getCodeData();
			JadxCodeData jadxCodeData;
			if (codeData == null) {
				jadxCodeData = new JadxCodeData();
				jadxCodeData.setRenames(new java.util.ArrayList<>());
				jadxCodeData.setComments(new java.util.ArrayList<>());
				decompiler.getArgs().setCodeData(jadxCodeData);
				codeData = jadxCodeData;
			} else {
				jadxCodeData = (JadxCodeData) codeData;
			}

			// Ensure renames list is mutable
			if (codeData.getRenames() == null || codeData.getRenames().isEmpty()) {
				jadxCodeData.setRenames(new java.util.ArrayList<>());
			}

			// Create rename
			ICodeRename rename = new JadxCodeRename(JadxNodeRef.forMth(method), sanitizedName);
			codeData.getRenames().add(rename);

			LOG.info("Renamed method {} to {}", methodRef, sanitizedName);

			// Trigger reload
			guiContext.reloadAllTabs();
			JOptionPane.showMessageDialog(null, "Method renamed to: " + sanitizedName, "Success",
					JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception e) {
			String errorMsg = e.getMessage();
			if (errorMsg == null || errorMsg.isEmpty()) {
				errorMsg = e.getClass().getSimpleName();
			}
			LOG.error("Failed to rename method: {}", methodRef, e);
			JOptionPane.showMessageDialog(null,
					"Failed to rename method: " + errorMsg + "\n\nMethod ref: " + methodRef
							+ "\nNew name: " + newName + "\n\nSee logs for details.",
					"Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void navigateToClassFromFilePath(JadxGuiContext guiContext, String filePath) {
		try {
			LOG.debug("Navigating to class from file path: '{}'", filePath);

			// Use cached root node if available
			RootNode root = cachedRootNode;
			if (root == null) {
				JadxDecompiler decompiler = pluginContext.getDecompiler();
				if (decompiler != null) {
					root = decompiler.getRoot();
					if (root != null) {
						cachedRootNode = root;
					}
				}
				if (root == null) {
					LOG.warn("RootNode not available for navigation");
					return;
				}
			}

			// Convert file path to class name
			// Example: "com/example/MyClass.java" -> "com.example.MyClass"
			String className = filePath;
			
			// Remove file extension
			int lastDot = className.lastIndexOf('.');
			if (lastDot > 0) {
				className = className.substring(0, lastDot);
			}
			
			// Replace path separators with package separators
			className = className.replace('/', '.').replace('\\', '.');

			LOG.debug("Converted file path '{}' to class name '{}'", filePath, className);

			// Find class node
			ClassNode classNode = root.resolveClass(className);
			if (classNode == null) {
				// Try searching by alias
				classNode = root.searchClassByFullAlias(className);
			}
			
			if (classNode == null) {
				LOG.debug("Class not found for file path: {} (class name: {})", filePath, className);
				return;
			}

			LOG.info("Found class node: {} (from file path: {})", classNode.getFullName(), filePath);

			// Navigate to class
			final ClassNode finalClassNode = classNode;
			guiContext.uiRun(() -> {
				try {
					guiContext.open((jadx.api.metadata.ICodeNodeRef) finalClassNode);
				} catch (Throwable t) {
					LOG.debug("Class navigation failed: {}", finalClassNode.getFullName(), t);
				}
			});
		} catch (Exception e) {
			LOG.warn("Failed to navigate to class from file path: {}", filePath, e);
		}
	}

	private void navigateToMethod(JadxGuiContext guiContext, JadxDecompiler decompiler, String methodRef) {
		try {
			LOG.debug("Navigating to method: '{}'", methodRef);

			// Use cached root node if available, otherwise get from decompiler (only if provided)
			RootNode root = cachedRootNode;
			if (root == null) {
				if (decompiler != null) {
					root = decompiler.getRoot();
					if (root != null) {
						cachedRootNode = root;
					}
				}
				if (root == null) {
					LOG.warn("RootNode not available for navigation");
					return;
				}
			}

			LOG.debug("Attempting to find method with reference: '{}'", methodRef);
			MethodNode methodNode = findMethodNodeByRef(root, methodRef);
			if (methodNode == null) {
				LOG.debug("Primary search failed, trying fallback search for: '{}'", methodRef);
				// Try fallback search
				methodNode = findMethodNodeByRefFallback(root, methodRef);
			}
			if (methodNode != null) {
				// STRICT VERIFICATION: Ensure we found the exact method we're looking for
				String foundMethodRef = methodNode.getMethodInfo().getFullName();
				String foundMethodId = methodNode.getMethodInfo().getFullId();
				boolean matches = foundMethodRef.equals(methodRef) || foundMethodId.equals(methodRef);

				LOG.debug("Found method node - Name: '{}', ID: '{}', Expected: '{}', Matches: {}",
						foundMethodRef, foundMethodId, methodRef, matches);

				if (!matches) {
					LOG.error("CRITICAL: Method reference mismatch! Expected '{}', but found method with name '{}' and ID '{}'. "
							+ "Navigation will be incorrect. This indicates a bug in method lookup.",
							methodRef, foundMethodRef, foundMethodId);
					// DO NOT navigate to wrong method - return early
					JOptionPane.showMessageDialog(null,
							String.format("Error: Could not find exact method match for '%s'.\n"
									+ "Found: %s (ID: %s)\n"
									+ "Navigation cancelled to prevent going to wrong location.",
									methodRef, foundMethodRef, foundMethodId),
							"Method Navigation Error",
							JOptionPane.ERROR_MESSAGE);
					return;
				}

				LOG.info("Successfully verified method match: {} (ID: {})", foundMethodRef, foundMethodId);
				// MethodNode implements ICodeNode, which extends ICodeNodeRef
				// Make final for lambda
				final MethodNode finalMethodNode = methodNode;
				final ClassNode parentClass = methodNode.getParentClass();
				final String finalMethodRef = methodRef;
				// Navigate asynchronously to avoid blocking UI and handle errors gracefully
				guiContext.uiRun(() -> {
					try {
						guiContext.open((jadx.api.metadata.ICodeNodeRef) finalMethodNode);
					} catch (Throwable t) {
						// Handle navigation errors gracefully (e.g., empty code, tokenization errors)
						// This can happen when method code is not loaded yet, is empty, or has tokenization bugs
						LOG.debug("Method navigation failed (tokenization error or empty code), trying class navigation: {}",
								finalMethodRef, t);
						// Fallback: navigate to the class instead
						try {
							if (parentClass != null) {
								guiContext.open((jadx.api.metadata.ICodeNodeRef) parentClass);
								LOG.debug("Successfully navigated to class instead: {}", parentClass.getFullName());
							}
						} catch (Throwable classEx) {
							LOG.debug("Class navigation also failed: {}", parentClass != null ? parentClass.getFullName() : "null",
									classEx);
						}
					}
				});
			} else {
				LOG.debug("Method not found for navigation: {}", methodRef);
			}
		} catch (Exception e) {
			LOG.warn("Failed to navigate to method: {}", methodRef, e);
		}
	}

	private MethodNode findMethodNodeByRef(RootNode root, String methodRef) {
		try {
			LOG.debug("findMethodNodeByRef: parsing methodRef '{}'", methodRef);
			int lastDot = methodRef.lastIndexOf('.');
			if (lastDot < 0) {
				LOG.debug("findMethodNodeByRef: no dot found in methodRef '{}'", methodRef);
				return null;
			}
			String className = methodRef.substring(0, lastDot);
			String methodPart = methodRef.substring(lastDot + 1);
			LOG.debug("findMethodNodeByRef: className='{}', methodPart='{}'", className, methodPart);

			// Resolve class - try multiple approaches for robustness
			ClassNode cls = null;

			// First try: resolve by original class name
			cls = root.resolveClass(className);

			// Second try: search by alias name (handles renamed classes)
			if (cls == null) {
				cls = root.searchClassByFullAlias(className);
			}

			// Third try: if className might be an alias, try searching all classes
			// This handles edge cases where the stored name doesn't match current state
			if (cls == null) {
				// Try to find class by matching both original and alias names
				for (ClassNode candidateCls : root.getClasses()) {
					String origName = candidateCls.getClassInfo().getFullName();
					String aliasName = candidateCls.getClassInfo().getAliasFullName();
					if (origName.equals(className) || (aliasName != null && aliasName.equals(className))) {
						cls = candidateCls;
						break;
					}
				}
			}

			if (cls == null) {
				LOG.debug("Class not found for method reference: {}", methodRef);
				return null;
			}

			// Check if methodRef includes signature (getFullId format) or just name (getFullName format)
			boolean hasSignature = methodPart.contains("(") && methodPart.contains(")");

			if (hasSignature) {
				// Method ref is in getFullId() format: "methodName(Args)ReturnType"
				// Use searchMethodByShortId which expects the signature
				MethodNode methodNode = cls.searchMethodByShortId(methodPart);
				if (methodNode != null) {
					// Verify it matches the expected full ID
					if (methodNode.getMethodInfo().getFullId().equals(methodRef)) {
						return methodNode;
					}
				}
			} else {
				// Method ref is in getFullName() format: "methodName" (backward compatibility)
				// Search by exact full name match first
				List<MethodNode> matchingMethods = new ArrayList<>();
				for (MethodNode m : cls.getMethods()) {
					if (m.getMethodInfo().getFullName().equals(methodRef)) {
						matchingMethods.add(m);
					}
				}

				// If we found exactly one match, return it
				if (matchingMethods.size() == 1) {
					return matchingMethods.get(0);
				}

				// If we found multiple methods with the same name (overloaded methods),
				// we can't distinguish them with just getFullName(). Return the first one
				// and log a warning.
				if (matchingMethods.size() > 1) {
					LOG.debug("Multiple methods found with same full name '{}' in class '{}'. Returning first match.",
							methodRef, className);
					return matchingMethods.get(0);
				}

				// Fallback: try by method name only
				MethodNode methodNode = cls.searchMethodByShortName(methodPart);
				if (methodNode != null) {
					// Verify it matches the expected full name
					if (methodNode.getMethodInfo().getFullName().equals(methodRef)) {
						return methodNode;
					}
				}
			}

			return null;
		} catch (Exception e) {
			LOG.debug("Failed to find method node by ref: {}", methodRef, e);
			return null;
		}
	}

	private MethodNode findMethodNodeByRefFallback(RootNode root, String methodRef) {
		try {
			// Check if methodRef includes signature (getFullId format) or just name (getFullName format)
			boolean hasSignature = methodRef.contains("(") && methodRef.contains(")");

			// Search through all classes for a method with matching reference
			for (ClassNode cls : root.getClasses()) {
				for (MethodNode methodNode : cls.getMethods()) {
					if (hasSignature) {
						// Match by full ID (includes signature)
						if (methodNode.getMethodInfo().getFullId().equals(methodRef)) {
							return methodNode;
						}
					} else {
						// Match by full name (backward compatibility)
						if (methodNode.getMethodInfo().getFullName().equals(methodRef)) {
							return methodNode;
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.debug("Error in fallback method search: {}", methodRef, e);
		}
		return null;
	}

	private String sanitizeMethodName(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}

		// Remove invalid characters and replace with underscore
		String sanitized = name.replaceAll("[^a-zA-Z0-9_$]", "_");

		// Ensure it starts with a valid character
		if (sanitized.isEmpty() || Character.isDigit(sanitized.charAt(0))) {
			sanitized = "m" + sanitized;
		}

		// Validate using NameMapper
		if (!NameMapper.isValidIdentifier(sanitized)) {
			// Try to fix common issues
			sanitized = sanitized.replaceAll("^[0-9]+", "m$0"); // Prefix numbers with 'm'
			if (!NameMapper.isValidIdentifier(sanitized)) {
				// Last resort: remove all invalid characters
				sanitized = sanitized.replaceAll("[^a-zA-Z0-9_$]", "");
				if (sanitized.isEmpty() || Character.isDigit(sanitized.charAt(0))) {
					sanitized = "m" + sanitized;
				}
				// If still invalid, return null
				if (!NameMapper.isValidIdentifier(sanitized)) {
					return null;
				}
			}
		}

		return sanitized;
	}

	private JavaMethod findMethodByRefFallback(JadxDecompiler decompiler, String methodRef) {
		try {
			// Search through all classes for a method with matching full name
			for (jadx.api.JavaClass javaClass : decompiler.getClasses()) {
				for (JavaMethod method : javaClass.getMethods()) {
					if (method.getFullName().equals(methodRef)) {
						return method;
					}
				}
			}
		} catch (Exception e) {
			// Ignore errors in fallback search
		}
		return null;
	}

	private int renameAllMethods(JadxGuiContext guiContext, JadxDecompiler decompiler,
			List<MagicStringsData.MethodCandidate> candidates) {
		int count = 0;
		int failed = 0;
		int invalid = 0;

		// Ensure codeData is initialized
		ICodeData codeData = decompiler.getArgs().getCodeData();
		JadxCodeData jadxCodeData;
		if (codeData == null) {
			jadxCodeData = new JadxCodeData();
			jadxCodeData.setRenames(new java.util.ArrayList<>());
			jadxCodeData.setComments(new java.util.ArrayList<>());
			decompiler.getArgs().setCodeData(jadxCodeData);
			codeData = jadxCodeData;
		} else {
			jadxCodeData = (JadxCodeData) codeData;
		}

		// Ensure renames list is mutable
		if (codeData.getRenames() == null || codeData.getRenames().isEmpty()) {
			jadxCodeData.setRenames(new java.util.ArrayList<>());
		}

		for (MagicStringsData.MethodCandidate candidate : candidates) {
			try {
				JavaMethod method = findMethodByRef(decompiler, candidate.getMethodRef());
				if (method == null) {
					// Try fallback search
					method = findMethodByRefFallback(decompiler, candidate.getMethodRef());
				}
				if (method == null) {
					failed++;
					LOG.debug("Method not found for rename: {}", candidate.getMethodRef());
					continue;
				}

				// Sanitize the candidate name
				String newName = sanitizeMethodName(candidate.getCandidate());
				if (newName == null || newName.isEmpty()) {
					invalid++;
					LOG.debug("Invalid method name for rename: '{}' (method: {})", candidate.getCandidate(),
							candidate.getMethodRef());
					continue;
				}

				ICodeRename rename = new JadxCodeRename(JadxNodeRef.forMth(method), newName);
				codeData.getRenames().add(rename);
				count++;
			} catch (Exception e) {
				failed++;
				LOG.warn("Failed to rename method: {}", candidate.getMethodRef(), e);
			}
		}

		if (count > 0) {
			guiContext.reloadAllTabs();
		}

		String message = String.format("Renamed %d methods successfully.", count);
		if (failed > 0 || invalid > 0) {
			message += String.format("\n%d methods could not be found.\n%d methods had invalid names.", failed,
					invalid);
		}
		JOptionPane.showMessageDialog(null, message, "Rename Complete", JOptionPane.INFORMATION_MESSAGE);

		LOG.info("Rename all completed: {} successful, {} failed, {} invalid", count, failed, invalid);
		return count;
	}

	private JPanel createAllStringsPanel(MagicStringsData data) {
		JPanel panel = new JPanel(new BorderLayout());

		String[] columnNames = { "String Value", "Method", "Class" };
		DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};

		for (MagicStringsData.StringInfo strInfo : data.getAllStrings()) {
			tableModel.addRow(new Object[] {
					strInfo.getValue(),
					strInfo.getMethodRef(),
					strInfo.getClassName()
			});
		}

		JTable table = new JTable(tableModel);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
		table.getColumnModel().getColumn(0).setPreferredWidth(400);
		table.getColumnModel().getColumn(1).setPreferredWidth(300);
		table.getColumnModel().getColumn(2).setPreferredWidth(300);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(scrollPane, BorderLayout.CENTER);

		JLabel infoLabel = new JLabel(String.format("Found %d strings", data.getAllStrings().size()));
		infoLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		panel.add(infoLabel, BorderLayout.SOUTH);

		return panel;
	}

	private static class LazyMethodCandidatesTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMN_NAMES = { "Method", "Current Name", "Candidate Name", "False Positive?",
				"Raw Strings" };
		private static final int MAX_RAW_STRING_DISPLAY_LENGTH = 200;

		private final List<MagicStringsData.MethodCandidate> candidates;
		private final RootNode root; // Use RootNode instead of JadxDecompiler (lightweight)
		private final Map<String, String> methodNameCache;
		// Cache for processed row data
		private final Map<Integer, RowData> rowDataCache = new ConcurrentHashMap<>();
		private SwingWorker<Void, Integer> backgroundWorker;
		private int loadedCount = 0;
		private JProgressBar progressBar;
		private JLabel infoLabel;

		// Helper class to store processed row data
		private static class RowData {
			final String methodRef;
			final String currentName;
			final String candidateName;
			final boolean isFalsePositive;
			final String rawString;
			final String fullRawString; // For tooltip

			RowData(String methodRef, String currentName, String candidateName, boolean isFalsePositive,
					String rawString, String fullRawString) {
				this.methodRef = methodRef;
				this.currentName = currentName;
				this.candidateName = candidateName;
				this.isFalsePositive = isFalsePositive;
				this.rawString = rawString;
				this.fullRawString = fullRawString;
			}
		}

		public LazyMethodCandidatesTableModel(List<MagicStringsData.MethodCandidate> candidates,
				RootNode root, Map<String, String> methodNameCache) {
			this.candidates = candidates;
			this.root = root;
			this.methodNameCache = methodNameCache;
		}

		public void startBackgroundLoading(JProgressBar progressBar, JLabel infoLabel) {
			this.progressBar = progressBar;
			this.infoLabel = infoLabel;

			backgroundWorker = new SwingWorker<Void, Integer>() {
				@Override
				protected Void doInBackground() throws Exception {
					int total = candidates.size();
					int batchSize = Math.max(100, total / 100); // Process 1% at a time, min 100
					List<Integer> batch = new ArrayList<>();

					for (int i = 0; i < total; i++) {
						processRow(i);
						batch.add(i);

						// Publish progress in batches
						if (batch.size() >= batchSize || i == total - 1) {
							publish(i + 1);
							batch.clear();
							// Small delay to keep UI responsive
							Thread.sleep(10);
						}
					}
					return null;
				}

				@Override
				protected void process(List<Integer> chunks) {
					if (!chunks.isEmpty()) {
						int latest = chunks.get(chunks.size() - 1);
						loadedCount = latest;
						if (progressBar != null) {
							progressBar.setValue(latest);
							progressBar.setString(String.format("Loading candidates... %d/%d", latest, candidates.size()));
						}
						// Fire table update for loaded rows
						fireTableRowsUpdated(0, Math.min(latest - 1, getRowCount() - 1));
					}
				}

				@Override
				protected void done() {
					if (progressBar != null) {
						progressBar.setValue(candidates.size());
						progressBar.setString("Complete");
						progressBar.setVisible(false);
					}
					if (infoLabel != null) {
						infoLabel.setText(String.format("Loaded %d candidates", candidates.size()));
					}
					// Final update
					fireTableDataChanged();
				}
			};

			backgroundWorker.execute();
		}

		private void processRow(int rowIndex) {
			if (rowDataCache.containsKey(rowIndex)) {
				return; // Already processed
			}

			MagicStringsData.MethodCandidate candidate = candidates.get(rowIndex);
			String methodRef = candidate.getMethodRef();
			String candidateName = candidate.getCandidate();
			Set<String> rawStrings = candidate.getRawStrings();

			// Get current method name (with caching)
			String currentName = getMethodNameCached(methodRef);
			boolean isFalsePositive = looksFalsePositive(currentName, candidateName);

			// Optimize raw string display
			String fullRawStr = String.join(", ", rawStrings);
			String displayRawStr = fullRawStr.length() > MAX_RAW_STRING_DISPLAY_LENGTH
					? fullRawStr.substring(0, MAX_RAW_STRING_DISPLAY_LENGTH) + "..."
					: fullRawStr;

			rowDataCache.put(rowIndex, new RowData(methodRef, currentName, candidateName, isFalsePositive,
					displayRawStr, fullRawStr));
		}

		private String getMethodNameCached(String methodRef) {
			return methodNameCache.computeIfAbsent(methodRef, ref -> {
				try {
					MethodNode methodNode = findMethodNodeByRef(root, ref);
					if (methodNode != null) {
						return methodNode.getName();
					}
				} catch (Exception e) {
					// Ignore
				}
				return "?";
			});
		}

		private MethodNode findMethodNodeByRef(RootNode root, String methodRef) {
			try {
				int lastDot = methodRef.lastIndexOf('.');
				if (lastDot < 0) {
					return null;
				}
				String className = methodRef.substring(0, lastDot);
				String methodPart = methodRef.substring(lastDot + 1);

				// Resolve class directly from RootNode (lightweight)
				ClassNode cls = root.resolveClass(className);
				if (cls == null) {
					cls = root.searchClassByFullAlias(className);
				}
				if (cls == null) {
					return null;
				}

				// Check if methodRef includes signature (getFullId format) or just name (getFullName format)
				boolean hasSignature = methodPart.contains("(") && methodPart.contains(")");

				if (hasSignature) {
					// Method ref is in getFullId() format: "methodName(Args)ReturnType"
					MethodNode methodNode = cls.searchMethodByShortId(methodPart);
					if (methodNode != null && methodNode.getMethodInfo().getFullId().equals(methodRef)) {
						return methodNode;
					}
				} else {
					// Method ref is in getFullName() format: "methodName" (backward compatibility)
					// Search by exact full name match first
					for (MethodNode m : cls.getMethods()) {
						if (m.getMethodInfo().getFullName().equals(methodRef)) {
							return m;
						}
					}
					// Fallback: search by method name only
					return cls.searchMethodByShortName(methodPart);
				}

				return null;
			} catch (Exception e) {
				return null;
			}
		}

		private boolean looksFalsePositive(String currentName, String candidate) {
			if (currentName == null || currentName.equals("?") || (currentName.startsWith("m") && currentName.length() <= 3)) {
				return false;
			}
			String currentLower = currentName.toLowerCase();
			String candidateLower = candidate.toLowerCase();
			return !currentLower.contains(candidateLower) && !candidateLower.contains(currentLower);
		}

		@Override
		public int getRowCount() {
			return candidates.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMN_NAMES.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMN_NAMES[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			// Trigger processing if not already done (lazy loading)
			if (!rowDataCache.containsKey(rowIndex)) {
				// Process immediately if not too many rows ahead
				if (rowIndex < loadedCount + 100) {
					processRow(rowIndex);
				} else {
					// Return placeholder for rows not yet processed
					switch (columnIndex) {
						case 0:
							return candidates.get(rowIndex).getMethodRef();
						case 2:
							return candidates.get(rowIndex).getCandidate();
						default:
							return "Loading...";
					}
				}
			}

			RowData data = rowDataCache.get(rowIndex);
			if (data == null) {
				return "Loading...";
			}

			switch (columnIndex) {
				case 0:
					return data.methodRef;
				case 1:
					return data.currentName;
				case 2:
					return data.candidateName;
				case 3:
					return data.isFalsePositive ? "Yes" : "No";
				case 4:
					return data.rawString;
				default:
					return "";
			}
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}

		public String getFullRawString(int rowIndex) {
			RowData rowData = rowDataCache.get(rowIndex);
			return rowData != null ? rowData.fullRawString : null;
		}
	}

	private static class LazyTopCandidatesTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private static final String[] COLUMN_NAMES = { "Method", "Current Name", "Candidate Name", "Score",
				"Source File", "String Data", "Raw Strings" };
		private static final int MAX_RAW_STRING_DISPLAY_LENGTH = 200;

		private final List<TopCandidateWithSourceFile> candidates;
		private final RootNode root;
		private final Map<String, String> methodNameCache;
		private final Map<Integer, TopRowData> rowDataCache = new ConcurrentHashMap<>();
		private SwingWorker<Void, Integer> backgroundWorker;
		private int loadedCount = 0;
		private JProgressBar progressBar;
		private JLabel infoLabel;

		// Helper class to store processed row data
		private static class TopRowData {
			final String methodRef;
			final String currentName;
			final String candidateName;
			final int score;
			final String sourceFile;
			final String stringData;
			final String rawString;
			final String fullRawString; // For tooltip

			TopRowData(String methodRef, String currentName, String candidateName, int score,
					String sourceFile, String stringData, String rawString, String fullRawString) {
				this.methodRef = methodRef;
				this.currentName = currentName;
				this.candidateName = candidateName;
				this.score = score;
				this.sourceFile = sourceFile;
				this.stringData = stringData;
				this.rawString = rawString;
				this.fullRawString = fullRawString;
			}
		}

		public LazyTopCandidatesTableModel(List<TopCandidateWithSourceFile> candidates,
				RootNode root, Map<String, String> methodNameCache) {
			this.candidates = candidates;
			this.root = root;
			this.methodNameCache = methodNameCache;
		}

		public void startBackgroundLoading(JProgressBar progressBar, JLabel infoLabel) {
			this.progressBar = progressBar;
			this.infoLabel = infoLabel;

			backgroundWorker = new SwingWorker<Void, Integer>() {
				@Override
				protected Void doInBackground() throws Exception {
					int total = candidates.size();
					int batchSize = Math.max(100, total / 100);
					List<Integer> batch = new ArrayList<>();

					for (int i = 0; i < total; i++) {
						processRow(i);
						batch.add(i);

						if (batch.size() >= batchSize || i == total - 1) {
							publish(i + 1);
							batch.clear();
							Thread.sleep(10);
						}
					}
					return null;
				}

				@Override
				protected void process(List<Integer> chunks) {
					if (!chunks.isEmpty()) {
						int latest = chunks.get(chunks.size() - 1);
						loadedCount = latest;
						if (progressBar != null) {
							progressBar.setValue(latest);
							progressBar.setString(String.format("Loading candidates... %d/%d", latest, candidates.size()));
						}
						fireTableRowsUpdated(0, Math.min(latest - 1, getRowCount() - 1));
					}
				}

				@Override
				protected void done() {
					if (progressBar != null) {
						progressBar.setValue(candidates.size());
						progressBar.setString("Complete");
						progressBar.setVisible(false);
					}
					if (infoLabel != null) {
						infoLabel.setText(String.format("Loaded %d candidates", candidates.size()));
					}
					fireTableDataChanged();
				}
			};

			backgroundWorker.execute();
		}

		private void processRow(int rowIndex) {
			if (rowDataCache.containsKey(rowIndex)) {
				return;
			}

			TopCandidateWithSourceFile candidate = candidates.get(rowIndex);
			String methodRef = candidate.getMethodRef();
			String candidateName = candidate.getCandidate();
			int score = candidate.getScore();
			String sourceFile = candidate.getSourceFile();
			String stringData = candidate.getStringData();
			Set<String> rawStrings = candidate.getRawStrings();

			// Get current method name (with caching)
			String currentName = getMethodNameCached(methodRef);

			// Optimize raw string display
			String fullRawStr = String.join(", ", rawStrings);
			String displayRawStr = fullRawStr.length() > MAX_RAW_STRING_DISPLAY_LENGTH
					? fullRawStr.substring(0, MAX_RAW_STRING_DISPLAY_LENGTH) + "..."
					: fullRawStr;

			rowDataCache.put(rowIndex, new TopRowData(methodRef, currentName, candidateName, score,
					sourceFile, stringData, displayRawStr, fullRawStr));
		}

		private String getMethodNameCached(String methodRef) {
			return methodNameCache.computeIfAbsent(methodRef, ref -> {
				try {
					MethodNode methodNode = findMethodNodeByRef(root, ref);
					if (methodNode != null) {
						return methodNode.getName();
					}
				} catch (Exception e) {
					// Ignore
				}
				return "?";
			});
		}

		private MethodNode findMethodNodeByRef(RootNode root, String methodRef) {
			try {
				int lastDot = methodRef.lastIndexOf('.');
				if (lastDot < 0) {
					return null;
				}
				String className = methodRef.substring(0, lastDot);
				String methodPart = methodRef.substring(lastDot + 1);

				ClassNode cls = root.resolveClass(className);
				if (cls == null) {
					cls = root.searchClassByFullAlias(className);
				}
				if (cls == null) {
					return null;
				}

				boolean hasSignature = methodPart.contains("(") && methodPart.contains(")");

				if (hasSignature) {
					MethodNode methodNode = cls.searchMethodByShortId(methodPart);
					if (methodNode != null && methodNode.getMethodInfo().getFullId().equals(methodRef)) {
						return methodNode;
					}
				} else {
					for (MethodNode m : cls.getMethods()) {
						if (m.getMethodInfo().getFullName().equals(methodRef)) {
							return m;
						}
					}
					return cls.searchMethodByShortName(methodPart);
				}

				return null;
			} catch (Exception e) {
				return null;
			}
		}

		@Override
		public int getRowCount() {
			return candidates.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMN_NAMES.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMN_NAMES[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			if (!rowDataCache.containsKey(rowIndex)) {
				if (rowIndex < loadedCount + 100) {
					processRow(rowIndex);
				} else {
					// Return placeholder for rows not yet processed
					TopCandidateWithSourceFile candidate = candidates.get(rowIndex);
					switch (columnIndex) {
						case 0:
							return candidate.getMethodRef();
						case 2:
							return candidate.getCandidate();
						case 3:
							return candidate.getScore();
						case 4:
							return candidate.getSourceFile();
						case 5:
							return candidate.getStringData();
						default:
							return "Loading...";
					}
				}
			}

			TopRowData data = rowDataCache.get(rowIndex);
			if (data == null) {
				return "Loading...";
			}

			switch (columnIndex) {
				case 0:
					return data.methodRef;
				case 1:
					return data.currentName;
				case 2:
					return data.candidateName;
				case 3:
					return data.score;
				case 4:
					return data.sourceFile;
				case 5:
					return data.stringData;
				case 6:
					return data.rawString;
				default:
					return "";
			}
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}

		public String getFullRawString(int rowIndex) {
			TopRowData rowData = rowDataCache.get(rowIndex);
			return rowData != null ? rowData.fullRawString : null;
		}
	}
}
