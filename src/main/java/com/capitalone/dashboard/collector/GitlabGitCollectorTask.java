package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.gitlab.DefaultGitlabGitClient;
import com.capitalone.dashboard.gitlab.GitlabGitClient;
import com.capitalone.dashboard.misc.HygieiaException;
import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by benathmane on 23/06/16.
 */

/**
 * CollectorTask that fetches Commit information from Gitlab
 */
@Component
public class GitlabGitCollectorTask  extends CollectorTask<Collector> {
    private static final Log LOG = LogFactory.getLog(GitlabGitCollectorTask.class);

	private static final long FOURTEEN_DAYS_MILLISECONDS = 14 * 24 * 60 * 60 * 1000;

	private final BaseCollectorRepository<Collector> collectorRepository;
	private final GitlabGitCollectorRepository gitlabGitCollectorRepository;
	private final CommitRepository commitRepository;
	private final GitRequestRepository gitRequestRepository;
	private final GitlabGitClient gitlabClient;
	private final GitlabSettings gitlabSettings;
	private final ComponentRepository dbComponentRepository;
	private final CollectorItemRepository collectorItemRepository;
	private final PipelineRepository pipelineRepository;
	private final ComponentRepository componentRepository;
	private final DashboardRepository dashboardRepository;

    @Autowired
    public GitlabGitCollectorTask(TaskScheduler taskScheduler,
								  BaseCollectorRepository<Collector> collectorRepository,
								  GitlabSettings gitlabSettings,
								  CommitRepository commitRepository,
								  GitRequestRepository gitRequestRepository,
								  GitlabGitCollectorRepository gitlabGitCollectorRepository,
								  DefaultGitlabGitClient gitlabClient,
								  ComponentRepository dbComponentRepository,
								  CollectorItemRepository collectorItemRepository,
								  PipelineRepository pipelineRepository,
								  ComponentRepository componentRepository, DashboardRepository dashboardRepository) {
        super(taskScheduler, "Gitlab");
        this.collectorRepository = collectorRepository;
        this.gitlabSettings = gitlabSettings;
        this.commitRepository = commitRepository;
		this.gitRequestRepository = gitRequestRepository;
        this.gitlabGitCollectorRepository = gitlabGitCollectorRepository;
        this.gitlabClient = gitlabClient;
        this.dbComponentRepository = dbComponentRepository;
        this.collectorItemRepository = collectorItemRepository;
        this.pipelineRepository = pipelineRepository;
		this.componentRepository = componentRepository;
		this.dashboardRepository = dashboardRepository;
	}

	@Override
	public Collector getCollector() {
		Collector protoType = new Collector();
		protoType.setName("Gitlab");
		protoType.setCollectorType(CollectorType.SCM);
		protoType.setOnline(true);
		protoType.setEnabled(true);

		Map<String, Object> allOptions = new HashMap<>();
		allOptions.put(GitlabGitRepo.REPO_URL, "");
		allOptions.put(GitlabGitRepo.BRANCH, "");
		allOptions.put(GitlabGitRepo.USER_ID, "");
		allOptions.put(GitlabGitRepo.PASSWORD, "");
		allOptions.put(GitlabGitRepo.LAST_UPDATE_TIME, new Date());
		protoType.setAllFields(allOptions);

		Map<String, Object> uniqueOptions = new HashMap<>();
		uniqueOptions.put(GitlabGitRepo.REPO_URL, "");
		uniqueOptions.put(GitlabGitRepo.BRANCH, "");
		protoType.setUniqueFields(uniqueOptions);
		return protoType;
	}

    @Override
    public BaseCollectorRepository<Collector> getCollectorRepository() {
        return collectorRepository;
    }

    @Override
    public String getCron() {
        return gitlabSettings.getCron();
    }

    @Override
    public void collect(Collector collector) {
        logBanner("Starting...");
        long start = System.currentTimeMillis();
        int repoCount = 0;
        int commitCount = 0;
		int pullCount = 0;
		int issueCount = 0;

        clean(collector);
        for (GitlabGitRepo repo : enabledRepos(collector)) {
			List<Commit> initialCommits = commitRepository.findByCollectorItemIdAndScmCommitTimestamp(collector.getId(), 0L);
			boolean isRepoFirstRun = (initialCommits == null || initialCommits.isEmpty()) || isFirstRun(start, repo.getLastUpdated());
			// moved last update date to collector item. This is to clean old data.
			repo.removeLastUpdateDate();

			try {
				// Step 1: Get all the commits
				LOG.info(repo.getOptions().toString() + "::" + repo.getBranch() + ":: get commits");
				List<Commit> commits = gitlabClient.getCommits(repo, isRepoFirstRun);
				commitCount = saveNewCommits(commitCount, repo, commits);
				processPipelineCommits(commits);

				// Step 2: Get all the issues
				LOG.info(repo.getOptions().toString() + "::" + repo.getBranch() + " get issues");
				List<GitRequest> allIssues = gitRequestRepository.findRequestNumberAndLastUpdated(repo.getId(),
						"issue");
				boolean isGetIssuesFirstRun = isGitRequestFirstRun(isRepoFirstRun, start, allIssues);
				List<GitRequest> issues = gitlabClient.getIssues(repo, isGetIssuesFirstRun);
				issueCount += processList(repo, issues, "issue");

				// Step 3: Get all the Merge Requests
				LOG.info(repo.getOptions().toString() + "::" + repo.getBranch() + "::get pulls");
				List<GitRequest> allMRs = gitRequestRepository.findRequestNumberAndLastUpdated(repo.getId(), "pull");
				Map<Long, String> mrCloseMap = allMRs.stream().collect(Collectors.toMap(GitRequest::getUpdatedAt,
						GitRequest::getNumber, (oldValue, newValue) -> oldValue));
				boolean isGetMergeRequestsFirstRun = isGitRequestFirstRun(isRepoFirstRun, start, allMRs);
				List<GitRequest> pulls = gitlabClient.getMergeRequests(repo, "all", isGetMergeRequestsFirstRun,
						mrCloseMap);
				pullCount += processList(repo, pulls, "pull");

				// save the fetched data to repository
				repo.setLastUpdated(System.currentTimeMillis());
				gitlabGitCollectorRepository.save(repo);
			} catch (HttpClientErrorException | ResourceAccessException e) {
				LOG.error("Failed to retrieve data, the repo or collector is most likey misconfigured: "
						+ repo.getRepoUrl(), e);

			} catch (MalformedURLException | HygieiaException ex) {
				LOG.error("Error fetching commits for:" + repo.getRepoUrl(), ex);
			} catch (Exception e) {
				LOG.error("XDFCE error");
				e.printStackTrace();
			}
			
			repoCount++;
        }
        log("Repo Count", start, repoCount);
        log("New Commits", start, commitCount);
		log("New Issues", start, issueCount);
		log("New Pulls", start, pullCount);

        log("Finished", start);
    }

    private boolean isGitRequestFirstRun(boolean isRepoFirstRun, long start, List<GitRequest> allGitRequests) {
        boolean isGitRequestFirstRun = isRepoFirstRun;

        if (!isGitRequestFirstRun) {
            if (allGitRequests.isEmpty()) {
                isGitRequestFirstRun = true;
            } else {
                isGitRequestFirstRun = isFirstRun(start, allGitRequests.get(0).getUpdatedAt());
            }
        }

        return isGitRequestFirstRun;
    }

	private boolean isFirstRun(long start, long lastUpdated) {
		return ((lastUpdated == 0) || ((start - lastUpdated) > FOURTEEN_DAYS_MILLISECONDS));
	}

	private int saveNewCommits(int commitCount, GitlabGitRepo repo, List<Commit> commits) {
		int totalCommitCount = commitCount;
		for (Commit commit : commits) {
			LOG.debug(commit.getTimestamp() + ":::" + commit.getScmCommitLog());
			if (isNewCommit(repo, commit)) {
				commit.setCollectorItemId(repo.getId());
				commitRepository.save(commit);
				totalCommitCount++;
			}
		}
		return totalCommitCount;
	}

	/**
	 * Finds or creates a pipeline for a dashboard collectoritem
	 * @param collectorItem
	 * @return
	 */
	protected Pipeline getOrCreatePipeline(CollectorItem collectorItem) {
		Pipeline pipeline = pipelineRepository.findByCollectorItemId(collectorItem.getId());
		if(pipeline == null){
			pipeline = new Pipeline();
			pipeline.setCollectorItemId(collectorItem.getId());
		}
		return pipeline;
	}

	private void processPipelineCommits(List<Commit> commits) {
		List<Commit> commitsToConsider = commits;

		if (gitlabSettings.isConsiderOnlyMergeCommits()) {
			LOG.info("Considering only merge commits to be added on the pipeline collection...");
			commitsToConsider = commits.stream()
					.filter(c -> c.getScmParentRevisionNumbers().size() > 1).collect(Collectors.toList());
		}
		if (commitsToConsider.isEmpty()) {
			LOG.info("No commits to be added on the pipeline collection during this scheduled run...");
			return;
		}
		List<Dashboard> allDashboardsForCommit = findAllDashboardsForCommit(commitsToConsider.get(0));
		String environmentName = PipelineStage.COMMIT.getName();
		List<Collector> collectorList = collectorRepository.findByCollectorType(CollectorType.Product);
		List<CollectorItem> collectorItemList = collectorItemRepository.findByCollectorIdIn(collectorList.stream().map(BaseModel::getId).collect(Collectors.toList()));

		for (CollectorItem collectorItem : collectorItemList) {
			List<String> dashBoardIds = allDashboardsForCommit.stream().map(d -> d.getId().toString()).collect(Collectors.toList());
			boolean dashboardId = dashBoardIds.contains(collectorItem.getOptions().get("dashboardId").toString());
			if(dashboardId) {
				Pipeline pipeline = getOrCreatePipeline(collectorItem);

				Map<String, EnvironmentStage> environmentStageMap = pipeline.getEnvironmentStageMap();
				if (environmentStageMap.get(environmentName) == null) {
					environmentStageMap.put(environmentName, new EnvironmentStage());
				}

				EnvironmentStage environmentStage = environmentStageMap.get(environmentName);
				if(environmentStage.getCommits() == null) {
					environmentStage.setCommits(new HashSet<>());
				}
				environmentStage.getCommits().addAll(commitsToConsider.stream()
						.map(commit -> new PipelineCommit(commit, commit.getTimestamp())).collect(Collectors.toSet()));
				pipelineRepository.save(pipeline);
			}
		}
	}

	private List<Dashboard> findAllDashboardsForCommit(Commit commit){
		if (commit.getCollectorItemId() == null) return new ArrayList<>();
		CollectorItem commitCollectorItem = collectorItemRepository.findOne(commit.getCollectorItemId());
		List<com.capitalone.dashboard.model.Component> components = componentRepository.findBySCMCollectorItemId(commitCollectorItem.getId());
		List<ObjectId> componentIds = components.stream().map(BaseModel::getId).collect(Collectors.toList());
		return dashboardRepository.findByApplicationComponentIdsIn(componentIds);
	}

	private int processList(GitlabGitRepo repo, List<GitRequest> entries, String type) {
		int count = 0;
		if (CollectionUtils.isEmpty(entries))
			return 0;

		for (GitRequest entry : entries) {
			LOG.debug(entry.getTimestamp() + ":::" + entry.getScmCommitLog());
			GitRequest existing = gitRequestRepository.findByCollectorItemIdAndNumberAndRequestType(repo.getId(),
					entry.getNumber(), type);

			if (existing == null) {
				entry.setCollectorItemId(repo.getId());
				count++;
			} else {
				entry.setId(existing.getId());
				entry.setCollectorItemId(repo.getId());
			}
			gitRequestRepository.save(entry);

			// fix merge commit type for squash merged and rebased merged MRs
			// MRs that were squash merged or rebase merged have only one parent
			if ("pull".equalsIgnoreCase(type) && "merged".equalsIgnoreCase(entry.getState())) {
				List<Commit> commits = commitRepository.findByScmRevisionNumber(entry.getScmRevisionNumber());
				for (Commit commit : commits) {
					if (null == commit.getType() || CommitType.Merge != commit.getType()) {
						commit.setType(CommitType.Merge);
						commitRepository.save(commit);
					}
				}
			}
		}

		return count;
	}

	private void clean(Collector collector) {
		Set<ObjectId> uniqueIDs = getUniqueIDs(collector);

		/**
		 * Logic: Get all the collector items from the collector_item collection
		 * for this collector. If their id is in the unique set (above), keep
		 * them enabled; else, disable them.
		 */
		List<GitlabGitRepo> repoList = new ArrayList<>();
		Set<ObjectId> gitID = new HashSet<>();
		gitID.add(collector.getId());
		for (GitlabGitRepo repo : gitlabGitCollectorRepository.findByCollectorIdIn(gitID)) {
			if (repo != null) {
				repo.setEnabled(uniqueIDs.contains(repo.getId()));
				repoList.add(repo);
			}
		}
		gitlabGitCollectorRepository.save(repoList);
	}

	private Set<ObjectId> getUniqueIDs(Collector collector) {
		Set<ObjectId> uniqueIDs = new HashSet<ObjectId>();

		/**
		 * Logic: For each component, retrieve the collector item list of the
		 * type SCM. Store their IDs in a unique set ONLY if their collector IDs
		 * match with GitLib collectors ID.
		 */
        for (com.capitalone.dashboard.model.Component comp : dbComponentRepository.findAll()) {
            if (comp.getCollectorItems() == null || comp.getCollectorItems().isEmpty()) continue;
            List<CollectorItem> itemList = comp.getCollectorItems().get(CollectorType.SCM);
            if (itemList == null) continue;
            for (CollectorItem ci : itemList) {
                if (ci != null && ci.getCollectorId().equals(collector.getId())) {
                    uniqueIDs.add(ci.getId());
                }
            }
        }

		return uniqueIDs;
	}


    private List<GitlabGitRepo> enabledRepos(Collector collector) {
        return gitlabGitCollectorRepository.findEnabledGitlabRepos(collector.getId());
    }

	private boolean isNewCommit(GitlabGitRepo repo, Commit commit) {
		return commitRepository.findByCollectorItemIdAndScmRevisionNumber(repo.getId(),
				commit.getScmRevisionNumber()) == null;
	}
}
