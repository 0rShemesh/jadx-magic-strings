/**
 * Pass to extract string constants and analyze them for source files and method names.
 * 
 * This pass runs after code is loaded and scans all methods in all classes to:
 * - Extract string constants from instruction nodes (CONST_STR instructions)
 * - Detect source file references using regex patterns (matching .java, .kt, etc.)
 * - Identify method name candidates using heuristics and scoring algorithms
 * - Store all extracted information in MagicStringsData for GUI display
 * 
 * The pass uses sophisticated scoring mechanisms to filter and rank method name
 * candidates based on various factors like rarity, context, and pattern matching.
 * 
 * @author 0rshemesh
 * @license Apache License 2.0
 */
package jadx.plugins.magicstrings.pass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;
import jadx.api.plugins.pass.JadxPassInfo;
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo;
import jadx.api.plugins.pass.types.JadxAfterLoadPass;
import jadx.core.deobf.NameMapper;
import jadx.core.dex.instructions.ConstStringNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.plugins.magicstrings.data.MagicStringsData;

/**
 * Pass to extract string constants and analyze them for source files and method names
 */
public class ExtractStringsPass implements JadxAfterLoadPass {
	private static final Logger LOG = LoggerFactory.getLogger(ExtractStringsPass.class);

	// Configuration constants
	private static final int MIN_STRING_LENGTH = 4;
	private static final int MIN_TOKEN_LENGTH = 4;
	private static final int MIN_CANDIDATE_LENGTH = 4;
	private static final int MAX_TOKEN_LENGTH_FOR_SEARCH = 500;
	private static final int MAX_CANDIDATES_PER_METHOD = 2; // Only keep top 2 candidates per method
	private static final int MIN_SCORE_TO_KEEP = 10; // Minimum score to keep a candidate (strict filtering)
	private static final int MIN_CAMELCASE_LENGTH_SHORT = 8;
	private static final int MIN_CAMELCASE_LENGTH_LONG = 12;
	private static final int MIN_UPPERCASE_FOR_BONUS = 2;
	private static final int FILE_EXTENSION_PROXIMITY = 30;
	private static final int LOG_INTERVAL_DIVISOR = 20;
	private static final int MIN_LOG_INTERVAL = 100;

	// Scoring constants
	private static final int SCORE_VALID_IDENTIFIER = 5;
	private static final int SCORE_STANDALONE = 15;
	private static final int SCORE_LOG_CONTEXT = 8;
	private static final int SCORE_POSITION_LOG_STATEMENT = 12;
	private static final int SCORE_CAMELCASE_LONG = 5;
	private static final int SCORE_CAMELCASE_SHORT = 3;
	private static final int PENALTY_BLACKLIST = -10;
	private static final int PENALTY_INVALID_IDENTIFIER = -10;
	private static final int PENALTY_ALL_UPPERCASE = -5;
	private static final int PENALTY_PACKAGE_PATH = -15;
	private static final int PENALTY_PACKAGE_PATH_FULL = -10;
	private static final int PENALTY_PARTIAL_WORD = -8;
	private static final int PENALTY_CLASS_NAME_IN_PATH = -5;
	private static final int PENALTY_NEAR_FILE_EXTENSION = -5;
	private static final int PENALTY_SHORT_SINGLE_WORD = -3;

	// Regex for Java/Kotlin source files
	private static final Pattern SOURCE_FILES_REGEXP = Pattern.compile(
			"([a-z_/\\\\][a-z0-9_/\\\\:\\-\\.@]+\\.(java|kt|kts|scala|groovy))($|:| )",
			Pattern.CASE_INSENSITIVE);

	// Regex for method names (Java style: strict lowerCamelCase)
	// Pattern: starts with lowercase letter, followed by alphanumeric, underscores, or camelCase words
	// Allows: lowercase letters, digits, underscores, and uppercase letters (for camelCase)
	// Examples: "getValue", "setValue2", "is_valid", "processData", "onCreate"
	private static final Pattern METHOD_NAMES_REGEXP = Pattern.compile(
			"^[a-z][a-z0-9_]*([A-Z][a-z0-9_]*)*$");

	// Pattern for finding method names within strings (with word boundaries)
	// Used when searching for method names as substrings within tokens
	// Matches: camelCase method names (getValue, processData) - requires at least one uppercase letter
	// Simple method names (get, set) are handled by exact matching on whole tokens
	// Requires word boundaries to avoid matching parts of longer words
	private static final Pattern METHOD_NAMES_FIND_PATTERN = Pattern.compile(
			"\\b[a-z][a-z0-9_]*(?:[A-Z][a-z0-9_]*)+\\b");

	// Common words that are not likely to be method names
	private static final Set<String> NOT_METHOD_NAMES = Set.of(
			"copyright", "license", "version", "cannot", "error", "invalid", "null",
			"warning", "general", "argument", "written", "report", "failed", "assert",
			"object", "integer", "unknown", "localhost", "native", "memory", "system",
			"write", "read", "open", "close", "help", "exit", "test", "return", "libs",
			"home", "ambiguous", "internal", "request", "inserting", "deleting", "removing",
			"updating", "adding", "assertion", "flags", "overflow", "enabled", "disabled",
			"enable", "disable", "virtual", "client", "server", "switch", "while", "offset",
			"abort", "panic", "static", "updated", "pointer", "reason", "default", "success",
			"expecting", "missing", "phrase", "unrecognized", "undefined", "corrupt", "corrupted",
			// Common English words
			"the", "not", "present", "downloading", "thumbnail", "file", "transfer", "incoming",
			"android", "google", "apps", "shared", "com", "java", "processor",
			"successfully", "queued", "download", "embedded", "initiate", "insert",
			"schedule", "finishing", "actions", "push", "message", "storing", "sender", "msisdn",
			"manual", "auto", "empty", "optional",
			// Additional common English words
			"for", "and", "or", "is", "are", "was", "were", "has", "have", "had", "with", "from",
			"this", "that", "which", "what", "when", "where", "who", "how",
			// Common package segments
			"org", "net", "io",
			// Common file-related words
			"path", "dir", "directory", "extension");


	// Pattern to detect class names (PascalCase - starts with uppercase)
	private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[A-Z][A-Za-z0-9]+$");

	// Pattern to detect file extensions
	private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("^.*\\.(java|kt|class|kts|scala|groovy)$");

	// Pattern to detect package paths
	private static final Pattern PACKAGE_PATH_FULL_PATTERN = Pattern.compile("^[a-z_][a-z0-9_]*(\\/[a-z_][a-z0-9_]*)+$");

	// Pattern to detect log literals (contains spaces, punctuation, format specifiers)
	private static final Pattern LOG_LITERAL_PATTERN = Pattern.compile(".*[\\s\\.\\?%\\{\\}].*");

	// Common file extensions to check
	private static final String[] FILE_EXTENSIONS = {".java", ".kt", ".class", ".kts", ".scala", ".groovy"};
	private static final String[] SOURCE_EXTENSIONS = {".java", ".kt", ".scala"};

	// Path separators
	private static final char PATH_SEPARATOR_FORWARD = '/';
	private static final char PATH_SEPARATOR_BACKWARD = '\\';
	private static final char PACKAGE_SEPARATOR = '.';

	private static class CandidateScore {
		private final String candidate;
		private final String sourceString;
		private int score;

		public CandidateScore(String candidate, String sourceString) {
			this.candidate = candidate;
			this.sourceString = sourceString;
			this.score = 0;
		}

		public String getCandidate() {
			return candidate;
		}

		public String getSourceString() {
			return sourceString;
		}

		public int getScore() {
			return score;
		}

		public void addScore(int points) {
			this.score += points;
		}
	}

	@Override
	public JadxPassInfo getInfo() {
		return new OrderedJadxPassInfo(
				"ExtractMagicStrings",
				"Extract information from string constants")
						.after("Deobfuscator");
	}

	@Override
	public void init(JadxDecompiler decompiler) {
		try {
			RootNode root = decompiler.getRoot();
			if (root == null) {
				return;
			}
			LOG.info("Magic Strings: Initializing extraction pass");
			long startTime = System.currentTimeMillis();

			MagicStringsData data = MagicStringsData.getData(root);
			extractStrings(root, data);

			long extractionTime = System.currentTimeMillis() - startTime;
			LOG.info("Magic Strings: Found {} method candidates, processing filters...", data.getMethodCandidates().size());

			// Process and filter candidates based on rarity
			long filterStartTime = System.currentTimeMillis();
			data.processFilteredCandidates();
			long filterTime = System.currentTimeMillis() - filterStartTime;

			long totalTime = System.currentTimeMillis() - startTime;
			LOG.info("Magic Strings: Complete. {} filtered candidates in {}ms (extraction: {}ms, filtering: {}ms)",
					data.getFilteredCandidates().size(), totalTime, extractionTime, filterTime);
		} catch (Exception e) {
			// Don't fail the build if our plugin has issues
			// Log error but continue
			LOG.error("Magic Strings plugin error", e);
		}
	}

	private void extractStrings(RootNode root, MagicStringsData data) {
		int totalClasses = root.getClasses().size();
		int processedClasses = 0;
		int processedMethods = 0;
		long startTime = System.currentTimeMillis();
		int logInterval = Math.max(MIN_LOG_INTERVAL, totalClasses / LOG_INTERVAL_DIVISOR);

		LOG.info("Magic Strings: Starting extraction from {} classes", totalClasses);

		for (ClassNode cls : root.getClasses()) {
			processedClasses++;
			if (processedClasses % logInterval == 0) {
				long elapsed = System.currentTimeMillis() - startTime;
				LOG.info("Magic Strings: Processed {}/{} classes ({} methods), {}ms elapsed",
						processedClasses, totalClasses, processedMethods, elapsed);
			}

			for (MethodNode mth : cls.getMethods()) {
				if (mth.isNoCode()) {
					continue;
				}
				// Ensure method is loaded
				if (!mth.isLoaded()) {
					try {
						mth.load();
					} catch (Exception e) {
						// Skip methods that fail to load
						continue;
					}
				}
				extractStringsFromMethod(cls, mth, data);
				processedMethods++;
			}
		}

		long totalTime = System.currentTimeMillis() - startTime;
		LOG.info("Magic Strings: Extraction complete. Processed {} classes, {} methods in {}ms",
				processedClasses, processedMethods, totalTime);
	}

	private void extractStringsFromMethod(ClassNode cls, MethodNode mth, MagicStringsData data) {
		try {
			String className = cls.getFullName();
			// Use getFullId() instead of getFullName() to uniquely identify methods
			// getFullId() includes signature: "className.methodName(Args)ReturnType"
			// This allows us to distinguish between overloaded methods
			String methodRef = mth.getMethodInfo().getFullId();
			String methodName = mth.getName();

			// Try to use blocks if available, otherwise use instructions array
			List<BlockNode> blocks = mth.getBasicBlocks();
			if (blocks != null && !blocks.isEmpty()) {
				for (BlockNode block : blocks) {
					if (block == null) {
						continue;
					}
					List<InsnNode> blockInstructions = block.getInstructions();
					if (blockInstructions != null) {
						for (InsnNode insn : blockInstructions) {
							if (insn != null) {
								processStringInstruction(insn, methodRef, methodName, className, data);
							}
						}
					}
				}
			} else {
				// Fallback to instructions array
				InsnNode[] instructions = mth.getInstructions();
				if (instructions != null) {
					for (InsnNode insn : instructions) {
						if (insn != null) {
							processStringInstruction(insn, methodRef, methodName, className, data);
						}
					}
				}
			}
		} catch (Exception e) {
			// Skip methods that cause issues
			// Don't fail the entire pass
		}
	}

	private void processStringInstruction(InsnNode insn, String methodRef, String methodName,
			String className, MagicStringsData data) {
		if (insn == null || insn.getType() != InsnType.CONST_STR) {
			return;
		}
		ConstStringNode constStr = (ConstStringNode) insn;
		String str = constStr.getString();

		if (str == null || str.length() < MIN_STRING_LENGTH) {
			return;
		}

		// Store string info
		data.getAllStrings().add(
				new MagicStringsData.StringInfo(str, methodRef, className));

		// Check for source file references
		checkSourceFile(str, methodRef, methodName, data);

		// Check for method names
		checkMethodNames(str, methodRef, data);
	}

	private void checkSourceFile(String str, String methodRef, String methodName, MagicStringsData data) {
		Matcher matcher = SOURCE_FILES_REGEXP.matcher(str);
		if (matcher.find()) {
			String filePath = matcher.group(1);
			data.getSourceFiles()
					.computeIfAbsent(filePath, k -> new ArrayList<>())
					.add(new MagicStringsData.SourceFileReference(methodRef, methodName, str));
		}
	}

	private void checkMethodNames(String str, String methodRef, MagicStringsData data) {
		// Early exit: skip if string is too short
		if (str == null || str.length() < MIN_STRING_LENGTH - 1) {
			return;
		}

		// Tokenization-first approach: split by commas if present, otherwise use whole string
		List<String> tokens = tokenizeString(str);

		// Collect all candidates with scores
		List<CandidateScore> candidatesWithScores = new ArrayList<>();

		// Reusable matchers for performance (created once per method call)
		Matcher exactMatcher = METHOD_NAMES_REGEXP.matcher("");
		Matcher findMatcher = METHOD_NAMES_FIND_PATTERN.matcher("");

		// Analyze each token separately
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if (token == null) {
				continue;
			}
			token = token.trim();

			// Skip empty or very short tokens
			if (token.length() < MIN_TOKEN_LENGTH) {
				continue;
			}

			// Apply exclusion filters first (early exit for performance)
			if (isExcludedToken(token)) {
				continue;
			}

			// Check if entire token matches method name pattern (exact match)
			exactMatcher.reset(token);
			if (exactMatcher.matches()) {
				CandidateScore candidateScore = new CandidateScore(token, str);
				scoreCandidate(candidateScore, token, i, tokens, str);
				// Only keep candidates with score above minimum threshold (strict filtering)
				if (candidateScore.getScore() >= MIN_SCORE_TO_KEEP) {
					candidatesWithScores.add(candidateScore);
				}
			} else if (token.length() < MAX_TOKEN_LENGTH_FOR_SEARCH) {
				// Only search within token if it's not too long (performance optimization)
				// Token doesn't match pattern, but might contain method name as substring
				// Use find pattern with word boundaries to avoid matching parts of longer words
				findMatcher.reset(token);
				while (findMatcher.find()) {
					String candidate = findMatcher.group(0);
					if (candidate.length() >= MIN_CANDIDATE_LENGTH) {
						// Additional validation: ensure candidate matches our method name pattern
						// Reuse exactMatcher for validation (to avoid false positives)
						exactMatcher.reset(candidate);
						if (exactMatcher.matches()) {
							CandidateScore candidateScore = new CandidateScore(candidate, str);
							scoreCandidate(candidateScore, token, i, tokens, str);
							// Only keep candidates with score above minimum threshold (strict filtering)
							if (candidateScore.getScore() >= MIN_SCORE_TO_KEEP) {
								candidatesWithScores.add(candidateScore);
							}
						}
					}
				}
			}
		}

		// Limit number of candidates to process (performance optimization)
		if (candidatesWithScores.isEmpty()) {
			return;
		}

		// Sort by score (descending) and keep only top candidates
		candidatesWithScores.sort(Comparator.comparingInt(CandidateScore::getScore).reversed());

		// Limit to top candidates per method (strict: only keep top 1-2)
		int maxCandidates = Math.min(MAX_CANDIDATES_PER_METHOD, candidatesWithScores.size());

		// Additional filtering: if we have multiple candidates, only keep those with score
		// within 5 points of the top score (to avoid keeping weak candidates)
		if (!candidatesWithScores.isEmpty() && maxCandidates > 1) {
			int topScore = candidatesWithScores.get(0).getScore();
			// Only keep candidates within 5 points of top score
			for (int i = maxCandidates - 1; i >= 1; i--) {
				if (candidatesWithScores.get(i).getScore() < topScore - 5) {
					maxCandidates = i; // Reduce max candidates
					break;
				}
			}
		}

		// Keep top candidates per method
		for (int i = 0; i < maxCandidates; i++) {
			CandidateScore cs = candidatesWithScores.get(i);
			String candidate = cs.getCandidate();
			data.getMethodCandidates()
					.computeIfAbsent(methodRef, k -> new HashSet<>())
					.add(candidate);
			data.getMethodRawStrings()
					.computeIfAbsent(methodRef, k -> new HashSet<>())
					.add(cs.getSourceString());
			// Track rarity (how many methods use this candidate)
			data.getCandidateRarity()
					.computeIfAbsent(candidate, k -> new HashSet<>())
					.add(methodRef);
			// Store score for later filtering
			data.getCandidateScores()
					.computeIfAbsent(methodRef, k -> new HashMap<>())
					.put(candidate, cs.getScore());
		}
	}

	private List<String> tokenizeString(String str) {
		List<String> tokens = new ArrayList<>();
		if (str == null || str.isEmpty()) {
			return tokens;
		}

		// Check if string contains commas for splitting
		if (str.contains(",")) {
			// Split by commas and process each part
			String[] parts = str.split(",");
			for (String part : parts) {
				if (part != null) {
					part = part.trim();
					// Remove surrounding quotes if present
					part = removeQuotes(part);
					if (!part.isEmpty()) {
						tokens.add(part);
					}
				}
			}
		}

		// If no commas found or no valid tokens, use the whole string
		if (tokens.isEmpty()) {
			String cleaned = removeQuotes(str.trim());
			if (!cleaned.isEmpty()) {
				tokens.add(cleaned);
			}
		}

		return tokens;
	}

	private String removeQuotes(String str) {
		if (str == null || str.length() < 2) {
			return str;
		}
		char first = str.charAt(0);
		char last = str.charAt(str.length() - 1);
		if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
			return str.substring(1, str.length() - 1);
		}
		return str;
	}

	private boolean isExcludedToken(String token) {
		if (token == null || token.isEmpty()) {
			return true;
		}

		int len = token.length();
		boolean startsWithUpper = len > 0 && Character.isUpperCase(token.charAt(0));

		// Quick check: if token contains path separators, it's likely a path
		if (token.indexOf(PATH_SEPARATOR_FORWARD) >= 0 || token.indexOf(PATH_SEPARATOR_BACKWARD) >= 0) {
			// Exclude package paths
			if (PACKAGE_PATH_FULL_PATTERN.matcher(token).matches()) {
				return true;
			}
		}

		// Quick check: if token contains file extension, exclude it
		if (token.indexOf(PACKAGE_SEPARATOR) >= 0) {
			// Check for file extensions
			for (String ext : FILE_EXTENSIONS) {
				if (token.endsWith(ext)) {
					if (FILE_EXTENSION_PATTERN.matcher(token).matches()) {
						return true;
					}
					break; // Found extension, no need to check others
				}
			}
		}

		// Quick check: if token starts with uppercase, might be PascalCase
		if (startsWithUpper) {
			// Exclude PascalCase (class names)
			if (CLASS_NAME_PATTERN.matcher(token).matches()) {
				return true;
			}
		}

		// Exclude log literals (contains spaces, punctuation, format specifiers)
		// Check for common log literal indicators first (performance optimization)
		if (token.indexOf(' ') >= 0 || token.indexOf('%') >= 0 
				|| token.indexOf('{') >= 0 || token.indexOf('}') >= 0) {
			if (LOG_LITERAL_PATTERN.matcher(token).matches()) {
				return true;
			}
		}

		return false;
	}

	private void scoreCandidate(CandidateScore cs, String token, int tokenIndex, List<String> tokens, String fullString) {
		String candidate = cs.getCandidate();
		if (candidate == null || candidate.isEmpty()) {
			return;
		}

		// Check against blacklist first - heavy penalty
		String candidateLower = candidate.toLowerCase();
		if (NOT_METHOD_NAMES.contains(candidateLower)) {
			cs.addScore(PENALTY_BLACKLIST);
			return;
		}

		// Validate Java identifier
		if (NameMapper.isValidIdentifier(candidate)) {
			cs.addScore(SCORE_VALID_IDENTIFIER);
		} else {
			cs.addScore(PENALTY_INVALID_IDENTIFIER);
			return;
		}

		// All uppercase is likely a constant
		if (isAllUpperCase(candidate)) {
			cs.addScore(PENALTY_ALL_UPPERCASE);
			return;
		}

		// High score: Standalone candidate (entire token is the candidate)
		if (isStandaloneCandidate(candidate, token)) {
			cs.addScore(SCORE_STANDALONE);
		}

		// High score: Position-based scoring (token index 1 in comma-separated log statements)
		scoreByPosition(cs, tokenIndex, tokens);

		// High score: Appears in log statement context
		if (isInLogContext(candidate, fullString, tokenIndex, tokens)) {
			cs.addScore(SCORE_LOG_CONTEXT);
		}

		// Medium score: Well-formed camelCase with multiple words
		int upperCaseCount = countUpperCaseLetters(candidate);
		int candidateLength = candidate.length();
		if (upperCaseCount >= MIN_UPPERCASE_FOR_BONUS && candidateLength > MIN_CAMELCASE_LENGTH_LONG) {
			cs.addScore(SCORE_CAMELCASE_LONG);
		} else if (upperCaseCount >= 1 && candidateLength > MIN_CAMELCASE_LENGTH_SHORT) {
			cs.addScore(SCORE_CAMELCASE_SHORT);
		}

		// Penalty: Part of package path (more aggressive)
		boolean containsPath = token.indexOf(PATH_SEPARATOR_FORWARD) >= 0 
				|| token.indexOf(PATH_SEPARATOR_BACKWARD) >= 0
				|| token.indexOf(PACKAGE_SEPARATOR) >= 0;
		if (containsPath) {
			if (isInPackagePath(candidate, token)) {
				cs.addScore(PENALTY_PACKAGE_PATH);
			}
			// Also check if candidate appears in full string as part of path
			if (isInPackagePath(candidate, fullString)) {
				cs.addScore(PENALTY_PACKAGE_PATH_FULL);
			}
		}

		// Penalty: Partial word (substring of longer word or class name)
		if (isPartialWord(candidate, token)) {
			cs.addScore(PENALTY_PARTIAL_WORD);
		}
		// Check if candidate is substring of class name in tokens
		if (isPartialOfClassName(candidate, tokens)) {
			cs.addScore(PENALTY_PARTIAL_WORD);
		}

		// Penalty: Single word, short
		if (upperCaseCount == 0 && candidateLength < MIN_CAMELCASE_LENGTH_SHORT) {
			cs.addScore(PENALTY_SHORT_SINGLE_WORD);
		}

		// Penalty: Class name in path context
		boolean containsFileExtension = hasFileExtension(token);
		if (CLASS_NAME_PATTERN.matcher(candidate).matches()) {
			if (containsPath || containsFileExtension) {
				cs.addScore(PENALTY_CLASS_NAME_IN_PATH);
			}
		}

		// Penalty: Near file extension
		if (containsFileExtension && isNearFileExtension(candidate, token)) {
			cs.addScore(PENALTY_NEAR_FILE_EXTENSION);
		}
	}

	private boolean isAllUpperCase(String str) {
		if (str == null || str.isEmpty()) {
			return false;
		}
		// Quick check: if any lowercase letter exists, it's not all uppercase
		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			if (Character.isLetter(c) && Character.isLowerCase(c)) {
				return false;
			}
		}
		return true;
	}

	private boolean hasFileExtension(String token) {
		if (token == null) {
			return false;
		}
		for (String ext : SOURCE_EXTENSIONS) {
			if (token.contains(ext)) {
				return true;
			}
		}
		return false;
	}

	private boolean isStandaloneCandidate(String candidate, String segment) {
		String trimmed = segment.trim();
		// Check if candidate equals the segment (allowing for minor whitespace)
		return trimmed.equals(candidate) || trimmed.equals("\"" + candidate + "\"");
	}

	private void scoreByPosition(CandidateScore cs, int tokenIndex, List<String> tokens) {
		// High score for position 1 (second token) - typical method name position in log statements
		if (tokenIndex == 1 && tokens.size() >= 3) {
			// Check if previous token looks like logger name and next token looks like log message
			String prevToken = tokens.get(0);
			String nextToken = tokens.size() > 2 ? tokens.get(2) : "";
			if (prevToken != null && nextToken != null) {
				prevToken = prevToken.trim();
				nextToken = nextToken.trim();
				// Logger names are often PascalCase or simple words
				boolean prevIsLogger = CLASS_NAME_PATTERN.matcher(prevToken).matches() || prevToken.length() < 20;
				// Log messages often contain spaces or punctuation
				boolean nextIsMessage = LOG_LITERAL_PATTERN.matcher(nextToken).matches() || nextToken.length() > 20;
				if (prevIsLogger && nextIsMessage) {
					cs.addScore(SCORE_POSITION_LOG_STATEMENT);
				}
			}
		}
	}

	private boolean isInLogContext(String candidate, String fullString, int tokenIndex, List<String> tokens) {
		Pattern logPattern1 = Pattern.compile(
				"[a-z][a-z0-9_/]*[/\\\\][A-Z][a-zA-Z0-9_]*\\s*[,\"']\\s*" + Pattern.quote(candidate)
						+ "\\s*[,\"']\\s*\\d+\\s*[,\"']\\s*[^,\"']+\\.java",
				Pattern.CASE_INSENSITIVE);
		if (logPattern1.matcher(fullString).find()) {
			return true;
		}

		// Pattern 2: candidate appears between package path and file extension with line number
		// Matches: ... "candidate", number, "...java"
		Pattern logPattern2 = Pattern.compile(
				"[,\"']\\s*" + Pattern.quote(candidate) + "\\s*[,\"']\\s*\\d+\\s*[,\"']\\s*[^,\"']+\\.java",
				Pattern.CASE_INSENSITIVE);
		if (logPattern2.matcher(fullString).find()) {
			return true;
		}

		// Pattern 3: logger, candidate, message pattern (comma-separated)
		if (tokenIndex == 1 && tokens.size() >= 3) {
			String prevToken = tokens.get(0).trim();
			String nextToken = tokens.size() > 2 ? tokens.get(2).trim() : "";
			if (CLASS_NAME_PATTERN.matcher(prevToken).matches() && LOG_LITERAL_PATTERN.matcher(nextToken).matches()) {
				return true;
			}
		}

		// Pattern 4: package/Class, candidate (method name in log call)
		Pattern logPattern4 = Pattern.compile(
				"[a-z][a-z0-9_/]*[/\\\\][A-Z][a-zA-Z0-9_]*\\s*[,\"']\\s*" + Pattern.quote(candidate),
				Pattern.CASE_INSENSITIVE);
		return logPattern4.matcher(fullString).find();
	}

	private boolean isInPackagePath(String candidate, String segment) {
		if (segment == null || candidate == null || candidate.isEmpty()) {
			return false;
		}

		int index = 0;
		int candidateLen = candidate.length();
		int segmentLen = segment.length();

		while ((index = segment.indexOf(candidate, index)) != -1) {
			boolean isPathSegment = false;

			// Check if it's between path separators
			if (index > 0) {
				char before = segment.charAt(index - 1);
				if (isPathSeparator(before)) {
					int afterIndex = index + candidateLen;
					if (afterIndex < segmentLen) {
						char after = segment.charAt(afterIndex);
						if (isPathSeparator(after)) {
							isPathSegment = true;
						}
					} else {
						isPathSegment = true;
					}
				}
			} else {
				int afterIndex = index + candidateLen;
				if (afterIndex < segmentLen) {
					char after = segment.charAt(afterIndex);
					if (isPathSeparator(after)) {
						isPathSegment = true;
					}
				}
			}

			// Also check if it's part of a package-like structure (com/app/android/...)
			if (!isPathSegment && index > 0) {
				int checkStart = Math.max(0, index - 20); // Check up to 20 chars back
				String beforeCandidate = segment.substring(checkStart, index);
				if (beforeCandidate.indexOf(PATH_SEPARATOR_FORWARD) >= 0 
						|| beforeCandidate.indexOf(PATH_SEPARATOR_BACKWARD) >= 0) {
					int afterIndex = index + candidateLen;
					if (afterIndex < segmentLen) {
						char after = segment.charAt(afterIndex);
						if (isPathSeparator(after)) {
							isPathSegment = true;
						}
					}
				}
			}

			if (isPathSegment) {
				return true;
			}

			index++;
		}
		return false;
	}


	private boolean isPathSeparator(char c) {
		return c == PATH_SEPARATOR_FORWARD || c == PATH_SEPARATOR_BACKWARD || c == PACKAGE_SEPARATOR;
	}


	private boolean isNearFileExtension(String candidate, String segment) {
		if (segment == null || candidate == null) {
			return false;
		}
		int candidateIndex = segment.indexOf(candidate);
		if (candidateIndex < 0) {
			return false;
		}

		// Check proximity to any source file extension
		for (String ext : SOURCE_EXTENSIONS) {
			int extIndex = segment.indexOf(ext);
			if (extIndex > 0 && candidateIndex < extIndex 
					&& candidateIndex > extIndex - FILE_EXTENSION_PROXIMITY) {
				return true;
			}
		}
		return false;
	}

	private boolean isPartialWord(String candidate, String fullString) {
		if (fullString == null || candidate == null || candidate.isEmpty()) {
			return false;
		}

		int index = 0;
		int candidateLen = candidate.length();
		int fullStringLen = fullString.length();

		while ((index = fullString.indexOf(candidate, index)) != -1) {
			// Check if it's part of a longer word
			if (index > 0) {
				char before = fullString.charAt(index - 1);
				if (Character.isLetterOrDigit(before)) {
					return true;
				}
			}
			int afterIndex = index + candidateLen;
			if (afterIndex < fullStringLen) {
				char after = fullString.charAt(afterIndex);
				if (Character.isLetterOrDigit(after)) {
					return true;
				}
			}
			index++;
		}
		return false;
	}

	
	private boolean isPartialOfClassName(String candidate, List<String> tokens) {
		if (candidate == null || candidate.isEmpty() || tokens == null) {
			return false;
		}

		for (String token : tokens) {
			if (token == null || token.isEmpty()) {
				continue;
			}
			if (!Character.isUpperCase(token.charAt(0))) {
				continue;
			}
			if (CLASS_NAME_PATTERN.matcher(token).matches()) {
				if (token.contains(candidate) && !token.equals(candidate)) {
					return true;
				}
			}
		}
		return false;
	}


	private int countUpperCaseLetters(String str) {
		if (str == null || str.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < str.length(); i++) {
			if (Character.isUpperCase(str.charAt(i))) {
				count++;
			}
		}
		return count;
	}
}
