package com.donotmiss.backend.mcp;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mcp")
public class McpController {
    private final McpToolContextService toolContextService;

    public McpController(McpToolContextService toolContextService) {
        this.toolContextService = toolContextService;
    }

    @PostMapping("/context")
    public McpDtos.ToolContextResponse context(@RequestBody(required = false) McpDtos.ToolContextRequest request) {
        return toolContextService.resolve(request);
    }
}
