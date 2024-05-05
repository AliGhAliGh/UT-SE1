package ir.ramtung.tinyme.messaging.event;

import java.time.LocalDateTime;

import ir.ramtung.tinyme.messaging.request.MatchinState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class SecurityStateChangedEvent extends Event {
    private LocalDateTime time;
    private String securityIsin;
    private MatchinState state;
}
