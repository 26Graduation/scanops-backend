package com.scanops.scan;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ZapAlert {
    private String alert;
    private String risk;
    private String url;
    private String param;
    private String description;
    private String solution;
}
