package ir.ramtung.tinyme.messaging.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChangeMatchingStateRq {
    private String securityIsin;
    private MatchingState targetState;

    public ChangeMatchingStateRq(String isin, MatchingState matchingState) {
        securityIsin = isin;
        targetState = matchingState;
    }
}
