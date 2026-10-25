package com.sis.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SearchIndexTest {
    private static SearchIndex students() {
        SearchIndex idx = new SearchIndex();
        idx.add("S101", "Aarav Das", "CSE, year 2", "CSE");
        idx.add("S102", "Aarav Bose", "IT, year 3", "IT");
        idx.add("S103", "Priya Raman", "ECE, year 2", "ECE");
        idx.add("S1042", "Meera Krishnan", "CSE, year 3", "CSE");
        return idx;
    }

    private static SearchIndex courses() {
        SearchIndex idx = new SearchIndex();
        idx.add("C1", "Distributed Systems", "3 credits", "CS301");
        idx.add("C2", "Machine Learning", "4 credits", "CS302");
        idx.add("C3", "Computer Networks", "3 credits", "CS204");
        return idx;
    }

    @Test
    void prefixFindsAllMatchesOnly() {
        List<SearchIndex.Result> r = students().search("aar", 8);
        assertEquals(2, r.size());
        assertTrue(r.stream().allMatch(x -> x.label().startsWith("Aarav")));
    }

    @Test
    void everyWordMustMatch() {
        List<SearchIndex.Result> r = students().search("aarav bo", 8);
        assertEquals(1, r.size());
        assertEquals("S102", r.get(0).id());
    }

    @Test
    void exactIdIsFirst() {
        assertEquals("S103", students().search("S103", 5).get(0).id());
        assertEquals("S1042", students().search("1042", 5).get(0).id());
    }

    @Test
    void courseCodeFindsCourse() {
        assertEquals("C2", courses().search("cs302", 5).get(0).id());
    }

    @Test
    void oneTypoIsTolerated() {
        assertEquals("C1", courses().search("distrbuted", 5).get(0).id());      // dropped letter
        assertEquals("C2", courses().search("machien learning", 5).get(0).id()); // swapped letters
        assertEquals("S103", students().search("priyaa", 5).get(0).id());        // extra letter
    }

    @Test
    void noMatchGivesEmptyList() {
        assertTrue(students().search("zzzzzz", 5).isEmpty());
        assertTrue(students().search("   ", 5).isEmpty());
    }

    @Test
    void limitIsRespected() {
        assertEquals(1, students().search("aar", 1).size());
    }

    @Test
    void editDistanceBasics() {
        assertEquals(0, SearchIndex.editDistance("abc", "abc", 1));
        assertEquals(1, SearchIndex.editDistance("abc", "acb", 1));
        assertEquals(1, SearchIndex.editDistance("abc", "abcd", 1));
        assertTrue(SearchIndex.editDistance("abc", "xyz", 1) > 1);
        assertFalse(SearchIndex.fuzzyPrefix("qqq", "distributed"));
    }

    @Test
    void benchmarkReachesGoodAccuracyOnSmallData() {
        Map<String, Object> res = SearchBenchmark.run(Map.of("student", students(), "course", courses()), 200, 7);
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) res.get("overall");
        assertTrue(((Number) overall.get("top5")).doubleValue() >= 95.0);
    }
}
