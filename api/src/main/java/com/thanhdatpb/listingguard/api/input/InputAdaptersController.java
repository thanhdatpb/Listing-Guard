package com.thanhdatpb.listingguard.api.input;

import com.thanhdatpb.listingguard.input.BuiltinAdapterCatalog;
import com.thanhdatpb.listingguard.input.InputAdapterDescriptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Read-only registry of input formats. Listing data is never accepted by this endpoint. */
@RestController
@RequestMapping("/api/v1/input-adapters")
@Tag(name = "Input adapters", description = "Read-only listing input format metadata.")
public class InputAdaptersController {
    public record AdapterView(String id, String marketplace, Set<String> formats, List<String> requiredColumns,
                              boolean experimental, String family) { }
    public record AdapterList(List<AdapterView> adapters) { }

    @GetMapping
    @Operation(summary = "List supported listing input adapters",
            description = "Lists read-only input adapters, accepted formats, marketplace targets and required header columns.")
    public AdapterList adapters() {
        return new AdapterList(BuiltinAdapterCatalog.descriptors().stream().map(InputAdaptersController::view).toList());
    }

    private static AdapterView view(InputAdapterDescriptor descriptor) {
        return new AdapterView(descriptor.id(), descriptor.marketplace(), descriptor.formats(),
                descriptor.requiredColumns(), descriptor.experimental(), descriptor.family());
    }
}
