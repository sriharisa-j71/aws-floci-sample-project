package com.example.regression.report;

import com.example.regression.db.TestCaseResultRepository;
import com.example.regression.db.TestRunRepository;
import com.example.regression.db.TestStepResultRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ReportGenerator {

    private final TestRunRepository testRunRepository;
    private final TestCaseResultRepository caseResultRepository;
    private final TestStepResultRepository stepResultRepository;

    public ReportGenerator(TestRunRepository testRunRepository,
                            TestCaseResultRepository caseResultRepository,
                            TestStepResultRepository stepResultRepository) {
        this.testRunRepository = testRunRepository;
        this.caseResultRepository = caseResultRepository;
        this.stepResultRepository = stepResultRepository;
    }

    public ReportModel generate(UUID runId) {
        var run = testRunRepository.findById(runId);
        var caseResults = caseResultRepository.findByRunId(runId);

        var caseEntries = caseResults.stream().map(cr -> {
            var stepResults = stepResultRepository.findByCaseResultId(cr.id());
            var stepEntries = stepResults.stream()
                    .map(sr -> new ReportModel.StepEntry(sr, java.util.List.of()))
                    .toList();
            return new ReportModel.CaseEntry(cr, stepEntries);
        }).toList();

        return new ReportModel(run, caseEntries);
    }
}
