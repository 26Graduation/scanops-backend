package com.scanops.scan;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ScanRequest {

    @NotBlank
    private String targetUrl;

    @Email
    @NotBlank
    private String ownerEmail;
}
