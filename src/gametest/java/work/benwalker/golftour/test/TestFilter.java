package work.benwalker.golftour.test;

/** {@code -Ponly=Name} runs only the game tests whose class name contains Name. */
final class TestFilter {
	private TestFilter() {
	}

	static boolean skip(Class<?> test) {
		String only = System.getProperty("golftour.only");
		return only != null && !only.isBlank() && !test.getSimpleName().toLowerCase().contains(only.toLowerCase());
	}
}
