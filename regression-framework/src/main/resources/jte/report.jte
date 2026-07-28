<%@param ReportModel model %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Test Report - ${model.run().suiteName()}</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 2em; }
        .pass { color: green; }
        .fail { color: red; }
        .summary { background: #f5f5f5; padding: 1em; border-radius: 4px; }
        table { border-collapse: collapse; width: 100%; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background: #f0f0f0; }
    </style>
</head>
<body>
    <h1>Test Report: ${model.run().suiteName()}</h1>
    <div class="summary">
        <p><strong>Status:</strong> <span class="${model.run().status() == 'PASSED' ? 'pass' : 'fail'}">${model.run().status()}</span></p>
        <p><strong>Duration:</strong> ${model.run().durationMs()}ms</p>
        <p><strong>Total Cases:</strong> ${model.run().totalCases()}</p>
    </div>

    <table>
        <tr><th>Case</th><th>Status</th><th>Duration</th><th>Steps</th></tr>
        @for (var entry : model.cases())
            <tr>
                <td>${entry.result().caseName()}</td>
                <td class="${entry.result().status() == 'PASSED' ? 'pass' : 'fail'}">${entry.result().status()}</td>
                <td>${entry.result().durationMs()}ms</td>
                <td>
                    <table>
                        <tr><th>Step</th><th>Status</th><th>Duration</th></tr>
                        @for (var step : entry.steps())
                            <tr>
                                <td>${step.result().stepName()}</td>
                                <td>${step.result().status()}</td>
                                <td>${step.result().durationMs()}ms</td>
                            </tr>
                        @endfor
                    </table>
                </td>
            </tr>
        @endfor
    </table>
</body>
</html>
