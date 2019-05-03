/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.transform;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.LocalParallelizer.TransformFunc;
import com.google.copybara.NonReversibleValidationException;
import com.google.copybara.TransformWork;
import com.google.copybara.Transformation;
import com.google.copybara.WorkflowOptions;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.treestate.TreeState.FileState;
import com.google.copybara.util.Glob;
import com.google.devtools.build.lib.events.Location;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

public class FilterReplace implements Transformation {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final WorkflowOptions workflowOptions;
  private final Pattern before;
  @Nullable private final Pattern after;
  private final int group;
  private final int reverseGroup;
  private final ReversibleFunction<String, String> mapping;
  private final Glob glob;
  private final Location location;

  public FilterReplace(WorkflowOptions workflowOptions, Pattern before, @Nullable Pattern after,
      int group, int reverseGroup,
      ReversibleFunction<String, String> mapping, Glob glob, Location location) {
    this.workflowOptions = workflowOptions;
    this.before = before;
    this.after = after;
    this.group = group;
    this.reverseGroup = reverseGroup;
    this.mapping = mapping;
    this.glob = glob;
    this.location = location;
  }

  @Override
  public void transform(TransformWork work) throws IOException, ValidationException {
    Path checkoutDir = work.getCheckoutDir();

    Iterable<FileState> files = work.getTreeState().find(glob.relativeTo(checkoutDir));
    BatchReplace batchReplace = new BatchReplace();
    workflowOptions.parallelizer().run(files, batchReplace);
    List<FileState> changed = batchReplace.getChanged();
    boolean matchedFile = batchReplace.matchedFile;
    logger.atInfo().log("Applied %s to %d files. %d changed.",
        this, Iterables.size(files), changed.size());

    work.getTreeState().notifyModify(changed);
    if (changed.isEmpty()) {
      workflowOptions.reportNoop(
          work.getConsole(),
          "Transformation '" + toString() + "' was a no-op because it didn't "
              + (matchedFile ? "change any of the matching files" : "match any file"),
          work.getIgnoreNoop());
    }
  }

  @Override
  public Transformation reverse() throws NonReversibleValidationException {
    if (after == null) {
      throw new NonReversibleValidationException(location, "No 'after' defined");
    }

    return new FilterReplace(workflowOptions, after, before, reverseGroup, group, mapping.reverse(),
        glob, location);
  }

  @Override
  public String describe() {
    return "Nested replace";
  }

  private class BatchReplace implements TransformFunc<FileState, Boolean> {

    private final List<FileState> changed = new ArrayList<>();
    private boolean matchedFile = false;

    public List<FileState> getChanged() {
      return changed;
    }


    @Override
    public Boolean run(Iterable<FileState> elements) throws IOException {
      Pattern pattern = Pattern.compile(before.pattern());
      List<FileState> changed = new ArrayList<>();
      boolean matchedFile = false;
      for (FileState file : elements) {
        if (Files.isSymbolicLink(file.getPath())) {
          continue;
        }
        matchedFile = true;
        String originalContent = new String(Files.readAllBytes(file.getPath()), UTF_8);
        Matcher matcher = pattern.matcher(originalContent);

        boolean anyReplace = false;
        StringBuilder result = new StringBuilder(originalContent.length());
        while (matcher.find()) {
          String val = matcher.group(FilterReplace.this.group);
          String res = mapping.apply(val);
          anyReplace |= !val.equals(res);
          if (group == 0) {
            matcher.appendReplacement(result, res);
          } else {
            String prefix = originalContent.substring(matcher.start(), matcher.start(group));
            String suffix = originalContent.substring(matcher.end(group), matcher.end());
            matcher.appendReplacement(result, prefix + res + suffix);
          }
        }

        if (!anyReplace) {
          continue;
        }

        matcher.appendTail(result);
        changed.add(file);
        Files.write(file.getPath(), result.toString().getBytes(UTF_8));
      }

      synchronized (this) {
        this.matchedFile |= matchedFile;
        this.changed.addAll(changed);
      }
      // We cannot return null here.
      return true;
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("before", before)
        .toString();
  }
}