        .matchesNext(MessageType.PROGRESS, "Git Destination: Fetching: file:.* master")
        .matchesNext(MessageType.WARNING, "Git Destination: 'master' doesn't exist in 'file://.*")
    thrown.expect(ValidationException.class);
  @Test
  public void test_force_rewrite_history() throws Exception {
    fetch = "master";
    push = "feature";

    destinationFiles = Glob.createGlob(ImmutableList.of("**"), ImmutableList.of("excluded.txt"));

    Path scratchTree = Files.createTempDirectory("GitDestinationTest-scratchTree");
    Files.write(scratchTree.resolve("excluded.txt"), "some content".getBytes(UTF_8));
    repo().withWorkTree(scratchTree).add().files("excluded.txt").run();
    repo().withWorkTree(scratchTree).simpleCommand("commit", "-m", "master change");

    Path file = workdir.resolve("test.txt");

    Files.write(file, "some content".getBytes());
    Writer<GitRevision> writer = newWriter();

    assertThat(writer.getDestinationStatus(DummyOrigin.LABEL_NAME, null)).isNull();
    process(writer, new DummyRevision("first_commit"));
    assertCommitHasOrigin("feature", "first_commit");

    Files.write(file, "changed".getBytes());

    process(writer, new DummyRevision("second_commit"));
    assertCommitHasOrigin("feature", "second_commit");

    GitRevision oldHead = repo().resolveReference("HEAD", /*contextRef=*/null);

    options.gitDestination.nonFastForwardPush = true;

    Files.write(file, "some content".getBytes());
    writer = newWriter();

    assertThat(writer.getDestinationStatus(DummyOrigin.LABEL_NAME, null)).isNull();
    process(writer, new DummyRevision("first_commit_2"));
    assertCommitHasOrigin("feature", "first_commit_2");

    Files.write(file, "changed".getBytes());

    process(writer, new DummyRevision("second_commit_2"));
    assertCommitHasOrigin("feature", "second_commit_2");

    assertThat(repo().log("master..feature").run()).hasSize(2);
  }

    processWithBaseline(newWriter(), ref, firstCommit);