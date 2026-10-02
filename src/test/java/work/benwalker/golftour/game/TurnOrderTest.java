package work.benwalker.golftour.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TurnOrderTest {
	static final UUID BEN = new UUID(0, 1), SAM = new UUID(0, 2), ALEX = new UUID(0, 3);
	static final List<UUID> HONOURS = List.of(BEN, SAM, ALEX);

	@Test
	void everyoneTeesOffInHonourOrderFirst() {
		var entries = List.of(
			new TurnOrder.Entry(BEN, false, true, 80),   // Ben has driven
			new TurnOrder.Entry(SAM, false, false, 170), // Sam and Alex are still on the tee
			new TurnOrder.Entry(ALEX, false, false, 170));
		assertEquals(SAM, TurnOrder.next(entries, HONOURS));
		assertEquals(ALEX, TurnOrder.next(entries, List.of(ALEX, BEN, SAM)));
	}

	@Test
	void thenTheFarthestFromTheHolePlays() {
		var entries = List.of(
			new TurnOrder.Entry(BEN, false, true, 40),
			new TurnOrder.Entry(SAM, false, true, 95),
			new TurnOrder.Entry(ALEX, false, true, 60));
		assertEquals(SAM, TurnOrder.next(entries, HONOURS));
	}

	@Test
	void playersWhoHaveHoledOutAreSkippedAndAnEmptyHoleEnds() {
		var entries = List.of(
			new TurnOrder.Entry(BEN, true, true, 0),
			new TurnOrder.Entry(SAM, false, true, 3),
			new TurnOrder.Entry(ALEX, true, true, 0));
		assertEquals(SAM, TurnOrder.next(entries, HONOURS));
		assertNull(TurnOrder.next(List.of(new TurnOrder.Entry(BEN, true, true, 0)), HONOURS));
	}

	@Test
	void bestScoreTakesTheHonourAndTiesKeepTheOrder() {
		var next = TurnOrder.honours(HONOURS, Map.of(BEN, 5, SAM, 4, ALEX, 4));
		assertEquals(List.of(SAM, ALEX, BEN), next);
	}
}
