package smarthire.jobs;

public record SavedSearch(long id, String name, JobSearch criteria, boolean active) {}
