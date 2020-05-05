package org.apache.druid.query;

import org.apache.druid.timeline.Overshadowable;
import org.apache.druid.timeline.TimelineLookup;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.joda.time.Interval;

import java.util.List;
import java.util.Map;

public interface MultiDataSource<T extends Overshadowable<T>> extends DataSource
{
  <T extends Overshadowable<T>> List<TimelineObjectHolder<String, T>> getSegments(
      List<Interval> intervals,
      Map<String, ? extends TimelineLookup<String, T>> timelineMap
  );
}
