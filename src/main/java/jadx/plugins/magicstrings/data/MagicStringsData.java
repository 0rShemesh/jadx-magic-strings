/**
 * Data structure to hold extracted information from string constants.
 * 
 * This class stores all the data extracted by ExtractStringsPass, including:
 * - Source file references: Maps file paths to methods that reference them
 * - Method candidates: Maps methods to potential method names found in strings
 * - Candidate scores: Scoring information for ranking candidates
 * - Candidate rarity: How many methods use each candidate (for filtering)
 * - Filtered candidates: High-confidence method name candidates
 * - All strings: Complete list of all extracted string constants
 * 
 * The data is stored as an attribute on the RootNode and can be accessed
 * by the GUI components for display and interaction.
 * 
 * @author 0rshemesh
 * @license Apache License 2.0
 */
package jadx.plugins.magicstrings.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jadx.api.plugins.input.data.attributes.IJadxAttrType;
import jadx.api.plugins.input.data.attributes.IJadxAttribute;
import jadx.core.dex.nodes.RootNode;

/**
 * Data structure to hold extracted information from string constants
 */
public class MagicStringsData implements IJadxAttribute {
	private static final IJadxAttrType<MagicStringsData> DATA = IJadxAttrType.create();

	// Map from source file path to list of [method reference, method name, string data]
	private final Map<String, List<SourceFileReference>> sourceFiles = new HashMap<>();

	// Map from method to candidate function names
	private final Map<String, Set<String>> methodCandidates = new HashMap<>();

	// Map from method to raw strings that reference it
	private final Map<String, Set<String>> methodRawStrings = new HashMap<>();

	// Map from candidate name to set of methods that use it (for rarity calculation)
	private final Map<String, Set<String>> candidateRarity = new HashMap<>();

	// Map from method to candidate scores (candidate -> score)
	private final Map<String, Map<String, Integer>> candidateScores = new HashMap<>();

	// Filtered list of method candidates (only candidates with rarity == 1)
	private final List<MethodCandidate> filteredCandidates = new ArrayList<>();

	// All extracted strings
	private final List<StringInfo> allStrings = new ArrayList<>();

	public MagicStringsData() {
	}

	public static MagicStringsData getData(RootNode root) {
		MagicStringsData data = root.getAttributes().get(DATA);
		if (data == null) {
			data = new MagicStringsData();
			root.getAttributes().add(data);
		}
		return data;
	}

	@Override
	public IJadxAttrType<MagicStringsData> getAttrType() {
		return DATA;
	}

	public Map<String, List<SourceFileReference>> getSourceFiles() {
		return sourceFiles;
	}

	public Map<String, Set<String>> getMethodCandidates() {
		return methodCandidates;
	}

	public Map<String, Set<String>> getMethodRawStrings() {
		return methodRawStrings;
	}

	public List<StringInfo> getAllStrings() {
		return allStrings;
	}

	public Map<String, Set<String>> getCandidateRarity() {
		return candidateRarity;
	}

	public Map<String, Map<String, Integer>> getCandidateScores() {
		return candidateScores;
	}

	public List<MethodCandidate> getFilteredCandidates() {
		return filteredCandidates;
	}

	public void processFilteredCandidates() {
		filteredCandidates.clear();
		int totalMethods = methodCandidates.size();
		int processed = 0;
		int logInterval = Math.max(1000, totalMethods / 10); // Log every 10% or every 1000 methods

		for (Map.Entry<String, Set<String>> entry : methodCandidates.entrySet()) {
			processed++;
			if (processed % logInterval == 0) {
				org.slf4j.LoggerFactory.getLogger(MagicStringsData.class)
						.debug("Magic Strings: Processing candidates {}/{}", processed, totalMethods);
			}

			String methodRef = entry.getKey();
			Set<String> candidates = entry.getValue();
			Set<String> rawStrings = methodRawStrings.getOrDefault(methodRef, Set.of());
			Map<String, Integer> scores = candidateScores.getOrDefault(methodRef, new HashMap<>());

			// Collect all candidates with their scores and rarity
			List<ScoredCandidate> allScoredCandidates = new ArrayList<>();
			for (String candidate : candidates) {
				Set<String> methodsUsingCandidate = candidateRarity.get(candidate);
				int rarity = methodsUsingCandidate != null ? methodsUsingCandidate.size() : 0;
				int score = scores.getOrDefault(candidate, 0);
				allScoredCandidates.add(new ScoredCandidate(candidate, score, rarity));
			}

			// Sort by: rarity == 1 first, then by score (descending)
			allScoredCandidates.sort((a, b) -> {
				// Prioritize candidates with rarity == 1
				if (a.rarity == 1 && b.rarity != 1) {
					return -1;
				}
				if (a.rarity != 1 && b.rarity == 1) {
					return 1;
				}
				// If same rarity priority, sort by score
				return Integer.compare(b.score, a.score);
			});

			// Keep only the strongest candidates: prioritize rarity == 1 with high scores
			if (!allScoredCandidates.isEmpty()) {
				ScoredCandidate topCandidate = allScoredCandidates.get(0);
				int topScore = topCandidate.score;

				// Strict filtering: only keep candidates with:
				// 1. Rarity == 1 and score >= 10 (unique to this method, high confidence)
				// 2. Or rarity == 1 and score >= 8 (unique to this method, good confidence)
				// 3. Or score >= 20 (very high-scoring candidates even if used by multiple methods)
				// 4. Or score >= topScore - 2 (within 2 points of highest, if topScore >= 15)
				// Limit to top 1-2 candidates per method
				int keptCount = 0;
				int maxKeep = 2; // Maximum candidates to keep per method
				
				for (ScoredCandidate sc : allScoredCandidates) {
					if (keptCount >= maxKeep) {
						break; // Already kept enough candidates
					}
					
					boolean shouldKeep = false;
					if (sc.rarity == 1 && sc.score >= 10) {
						shouldKeep = true; // Unique and high confidence
					} else if (sc.rarity == 1 && sc.score >= 8) {
						shouldKeep = true; // Unique and good confidence
					} else if (sc.score >= 20) {
						shouldKeep = true; // Very high score
					} else if (topScore >= 15 && sc.score >= topScore - 2 && keptCount == 0) {
						// Only keep if it's the top candidate and within 2 points
						shouldKeep = true;
					}

					if (shouldKeep) {
						filteredCandidates.add(new MethodCandidate(methodRef, sc.candidate, rawStrings));
						keptCount++;
					}
				}
			}
		}
	}

	private static class ScoredCandidate {
		final String candidate;
		final int score;
		final int rarity;

		ScoredCandidate(String candidate, int score, int rarity) {
			this.candidate = candidate;
			this.score = score;
			this.rarity = rarity;
		}
	}

	public static class MethodCandidate {
		private final String methodRef;
		private final String candidate;
		private final Set<String> rawStrings;

		public MethodCandidate(String methodRef, String candidate, Set<String> rawStrings) {
			this.methodRef = methodRef;
			this.candidate = candidate;
			this.rawStrings = rawStrings;
		}

		public String getMethodRef() {
			return methodRef;
		}

		public String getCandidate() {
			return candidate;
		}

		public Set<String> getRawStrings() {
			return rawStrings;
		}
	}

	public static class SourceFileReference {
		private final String methodRef;
		private final String methodName;
		private final String stringData;

		public SourceFileReference(String methodRef, String methodName, String stringData) {
			this.methodRef = methodRef;
			this.methodName = methodName;
			this.stringData = stringData;
		}

		public String getMethodRef() {
			return methodRef;
		}

		public String getMethodName() {
			return methodName;
		}

		public String getStringData() {
			return stringData;
		}
	}

	public static class StringInfo {
		private final String value;
		private final String methodRef;
		private final String className;

		public StringInfo(String value, String methodRef, String className) {
			this.value = value;
			this.methodRef = methodRef;
			this.className = className;
		}

		public String getValue() {
			return value;
		}

		public String getMethodRef() {
			return methodRef;
		}

		public String getClassName() {
			return className;
		}
	}
}
