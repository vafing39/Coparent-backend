package com.vynk.coparent_backend.family.mapper;

import com.vynk.coparent_backend.family.dto.ChildResponse;
import com.vynk.coparent_backend.family.dto.CustodyScheduleResponse;
import com.vynk.coparent_backend.family.dto.FamilyResponse;
import com.vynk.coparent_backend.family.dto.MemberResponse;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.CustodySchedule;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface FamilyMapper {

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "firstName", source = "user.firstName")
    MemberResponse toResponse(FamilyMember member);

    ChildResponse toResponse(Child child);

    List<ChildResponse> toChildResponses(List<Child> children);

    List<MemberResponse> toMemberResponses(List<FamilyMember> members);

    CustodyScheduleResponse toResponse(CustodySchedule schedule);

    /** The family as seen by {@code me}. */
    default FamilyResponse toResponse(Family family, FamilyMember me, List<FamilyMember> members,
                                      List<Child> children, CustodySchedule schedule) {
        return new FamilyResponse(family.getId(), family.getName(), family.getVacationZone(), me.getRole(),
                members.size() < 2, toMemberResponses(members), toChildResponses(children),
                schedule == null ? null : toResponse(schedule));
    }
}
