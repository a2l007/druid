package org.apache.druid.data.input.impl;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.List;

public class DefaultInputChooser implements InputChooser
{
  public static final String TYPE_KEY = "default";

  @Override
  public List<String> chooseFiles()
  {
    return ImmutableList.of();
  }
}
