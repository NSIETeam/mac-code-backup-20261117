package cn.siyue.core;

import jakarta.validation.constraints.*;

/** Existing activity filters grouped without changing the HTTP query contract. */
public record CrmActivityQuery(@Size(max=120) String campusId,
        @Pattern(regexp="scope|created|participating") String view,
        @Pattern(regexp="event|campaign|referral|other") String activityType,
        @Pattern(regexp="active|closed") String status,@Size(max=120) String query,
        @Min(1) @Max(100000) Integer page,@Min(1) @Max(100) Integer size) {
    public CrmActivityQuery {
        if(view==null)view="scope";
        if(page==null)page=1;
        if(size==null)size=50;
    }
}
