package com.intentreactor.sandtrain;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sand")
public class SandTrainController {

    private final SandDataCollector collector;
    private final SandDatasetExporter exporter;

    public SandTrainController(SandDataCollector collector, SandDatasetExporter exporter) {
        this.collector = collector;
        this.exporter = exporter;
    }

    @GetMapping("/training-data/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "totalRecords", collector.getTotalRecords(),
                "sessionCount", collector.getSessionCount()
        ));
    }

    @GetMapping("/training-data/export")
    public ResponseEntity<byte[]> export() {
        String jsonl = exporter.toJsonl(collector.getRecords());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sand-training.jsonl\"")
                .contentType(MediaType.parseMediaType("application/jsonlines"))
                .body(jsonl.getBytes());
    }
}
