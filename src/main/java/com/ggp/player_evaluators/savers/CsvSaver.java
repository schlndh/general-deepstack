package com.ggp.player_evaluators.savers;

import com.ggp.player_evaluators.EvaluatorEntry;
import com.ggp.player_evaluators.IPlayerEvaluationSaver;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.Writer;

public class CsvSaver implements IPlayerEvaluationSaver {
    private CSVPrinter csvOut;

    public CsvSaver(Writer output) throws IOException {
        csvOut = new CSVPrinter(output,
                CSVFormat.EXCEL.withHeader("intended_time", "time", "intended_init_time", "init_time", "states", "init_states", "path_states", "path_states_min", "path_states_max", "exp", "first_act_exp"));
    }

    public void add(EvaluatorEntry e, double exploitability, double firstActExp) throws IOException {
        if (csvOut == null) throw new RuntimeException("Cannot add entry to closed saver!");
        csvOut.printRecord(e.getIntendedActTimeMs(), e.getEntryTimeMs(), e.getIntendedInitTimeMs(), e.getAvgInitTimeMs(),
                e.getAvgVisitedStates(), e.getAvgInitVisitedStates(), e.getPathStatesAvg(), e.getPathStatesMin(),
                e.getPathStatesMax(), exploitability, firstActExp);
        csvOut.flush();
    }

    public void close() throws IOException {
        csvOut.close();
        csvOut = null;
    }
}
