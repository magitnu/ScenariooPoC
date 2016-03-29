/* scenarioo-server
 * Copyright (C) 2014, scenarioo.org Development Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.scenarioo.business.builds;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.scenarioo.business.aggregator.ScenarioDocuAggregator;
import org.scenarioo.business.lastSuccessfulScenarios.LastSuccessfulScenariosBuild;
import org.scenarioo.dao.aggregates.ScenarioDocuAggregationDAO;
import org.scenarioo.model.docu.aggregates.branches.BranchBuilds;
import org.scenarioo.model.docu.aggregates.branches.BuildImportStatus;
import org.scenarioo.model.docu.aggregates.branches.BuildImportSummary;
import org.scenarioo.repository.ConfigurationRepository;
import org.scenarioo.repository.RepositoryLocator;
import org.scenarioo.rest.base.BuildIdentifier;

/**
 * Takes care of importing builds.
 */
public class BuildImporter {

	private static final Logger LOGGER = Logger.getLogger(BuildImporter.class);
	
	private static final ConfigurationRepository configurationRepository = RepositoryLocator.INSTANCE
			.getConfigurationRepository();
	
	/**
	 * Current state for all builds whether imported and aggregated correctly.
	 */
	private Map<BuildIdentifier, BuildImportSummary> buildImportSummaries = new HashMap<BuildIdentifier, BuildImportSummary>();

	/**
	 * Builds that have been scheduled for processing (waiting for import)
	 */
	private final Set<BuildIdentifier> buildsInProcessingQueue = new HashSet<BuildIdentifier>();
	
	/**
	 * Builds currently beeing imported.
	 */
	Set<BuildIdentifier> buildsBeeingImported = new HashSet<BuildIdentifier>();
	
	/**
	 * Executor to execute one import task after the other asynchronously.
	 */
	private final ExecutorService asyncBuildImportExecutor = newAsyncBuildImportExecutor();
	
	private final LastSuccessfulScenariosBuild lastSuccessfulScenarioBuild = new LastSuccessfulScenariosBuild();
	
	// TODO pforster: start proto
	private final BuildComparator buildComparator = new BuildComparator();
	// TODO pforster: end proto

	public Map<BuildIdentifier, BuildImportSummary> getBuildImportSummaries() {
		return buildImportSummaries;
	}
	
	public List<BuildImportSummary> getBuildImportSummariesAsList() {
		return new ArrayList<BuildImportSummary>(buildImportSummaries.values());
	}
	
	public synchronized void updateBuildImportStates(final List<BranchBuilds> branchBuildsList,
			final Map<BuildIdentifier, BuildImportSummary> loadedBuildSummaries) {
		Map<BuildIdentifier, BuildImportSummary> result = new HashMap<BuildIdentifier, BuildImportSummary>();
		for (BranchBuilds branchBuilds : branchBuildsList) {
			for (BuildLink buildLink : branchBuilds.getBuilds()) {
				// Take existent summary or create new one.
				BuildIdentifier buildIdentifier = new BuildIdentifier(branchBuilds.getBranch().getName(), buildLink
						.getBuild().getName());
				BuildImportSummary buildSummary = loadedBuildSummaries.get(buildIdentifier);
				if (buildSummary == null) {
					buildSummary = new BuildImportSummary(branchBuilds.getBranch().getName(), buildLink.getBuild());
				}
				ScenarioDocuAggregator aggregator = new ScenarioDocuAggregator(buildSummary);
				aggregator.updateBuildSummary(buildLink);
				if (buildsBeeingImported.contains(buildIdentifier)) {
					buildSummary.setStatus(BuildImportStatus.PROCESSING);
				} else if (buildsInProcessingQueue.contains(buildIdentifier)) {
					buildSummary.setStatus(BuildImportStatus.QUEUED_FOR_PROCESSING);
				}
				result.put(buildIdentifier, buildSummary);
			}
		}
		saveBuildImportSummaries(result);
		buildImportSummaries = result;
	}
	
	public synchronized void submitUnprocessedBuildsForImport(final AvailableBuildsList availableBuilds) {
		List<BuildImportSummary> buildsSortedByDateDescending = BuildByDateSorter
				.sortBuildsByDateDescending(buildImportSummaries.values());

		for (BuildImportSummary buildImportSummary : buildsSortedByDateDescending) {
			if (buildImportSummary != null && buildImportSummary.getStatus().isImportNeeded()) {
				submitBuildForImport(availableBuilds, buildImportSummary.getIdentifier());
			}
		}
	}
	
	public synchronized void submitBuildForReimport(final AvailableBuildsList availableBuilds,
			final BuildIdentifier buildIdentifier) {
		removeImportedBuildAndDerivedData(availableBuilds, buildIdentifier);
		submitBuildForImport(availableBuilds, buildIdentifier);
		saveBuildImportSummaries(buildImportSummaries);
	}
	
	/**
	 * Remove a build from the available builds list and mark it as unprocessed, also remove any available derived data
	 * that mark this build as processed.
	 */
	private synchronized void removeImportedBuildAndDerivedData(final AvailableBuildsList availableBuilds,
			final BuildIdentifier buildIdentifier) {
		
		// Do not do anything when build is unknown or already queued for asynchronous processing
		final BuildImportSummary summary = buildImportSummaries.get(buildIdentifier);
		if (summary == null || buildsInProcessingQueue.contains(buildIdentifier)) {
			return;
		}
		
		availableBuilds.removeBuild(buildIdentifier);
		summary.setStatus(BuildImportStatus.UNPROCESSED);
		ScenarioDocuAggregator aggregator = new ScenarioDocuAggregator(summary);
		aggregator.removeAggregatedDataForBuild();
	}
	
	/**
	 * Submit any build for import.
	 */
	private synchronized void submitBuildForImport(final AvailableBuildsList availableBuilds,
			final BuildIdentifier buildIdentifier) {
		
		// Do not do anything when build is unknown or already queued
		final BuildImportSummary summary = buildImportSummaries.get(buildIdentifier);
		if (summary == null || buildsInProcessingQueue.contains(buildIdentifier)) {
			return;
		}
		
		LOGGER.info("  Submitting build for import: " + buildIdentifier.getBranchName() + "/"
				+ buildIdentifier.getBuildName());
		buildsInProcessingQueue.add(buildIdentifier);
		summary.setStatus(BuildImportStatus.QUEUED_FOR_PROCESSING);
		asyncBuildImportExecutor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					importBuild(availableBuilds, summary);
				} catch (Throwable e) {
					LOGGER.error("Unexpected error on build import.", e);
				}
			}
		});
	}
	
	private void importBuild(final AvailableBuildsList availableBuilds, BuildImportSummary summary) {
		
		BuildImportLogAppender buildImportLog = null;
		
		try {
			buildImportLog = BuildImportLogAppender.createAndRegisterForLogsOfBuild(summary.getIdentifier());
			buildsBeeingImported.add(summary.getIdentifier());
			
			LOGGER.info(" ============= START OF BUILD IMPORT ================");
			LOGGER.info("  Importing build: " + summary.getIdentifier().getBranchName() + "/"
					+ summary.getIdentifier().getBuildName());
			LOGGER.info("  This might take a while ...");
			
			summary = buildImportSummaries.get(summary.getIdentifier());
			summary.setStatus(BuildImportStatus.PROCESSING);
			
			ScenarioDocuAggregator aggregator = new ScenarioDocuAggregator(summary);
			if (!aggregator.isAggregatedDataForBuildAlreadyAvailableAndCurrentVersion()) {
				aggregator.calculateAggregatedDataForBuild();
				addSuccessfullyImportedBuild(availableBuilds, summary);
				lastSuccessfulScenarioBuild.updateLastSuccessfulScenarioBuild(summary, this, availableBuilds);
				LOGGER.info("  SUCCESS on importing build: " + summary.getIdentifier().getBranchName() + "/"
						+ summary.getIdentifier().getBuildName());
			} else {
				addSuccessfullyImportedBuild(availableBuilds, summary);
				LOGGER.info("  ADDED ALREADY IMPORTED build: " + summary.getIdentifier().getBranchName() + "/"
						+ summary.getIdentifier().getBuildName());
			}

			// TODO pforster: proto start
			// TODO pforster: get compare builds from configuration
			List<BuildIdentifier> compareBuildIdentifiers = new LinkedList<BuildIdentifier>();
			compareBuildIdentifiers.add(new BuildIdentifier("gh-pages", "2016-03-25T15:26:39.017"));

			for (BuildIdentifier compareBuildIdentifier : compareBuildIdentifiers) {
				LOGGER.info("  START comparison between " + summary.getIdentifier().getBranchName() + "/"
						+ summary.getIdentifier().getBuildName() + " and " + compareBuildIdentifier.getBranchName()
						+ "/"
						+ compareBuildIdentifier.getBuildName());

				buildComparator.compareBuilds(summary.getIdentifier(), compareBuildIdentifier);

				LOGGER.info("  FINISHED comparison between " + summary.getIdentifier().getBranchName() + "/"
						+ summary.getIdentifier().getBuildName() + " and " + compareBuildIdentifier.getBranchName()
						+ "/"
						+ compareBuildIdentifier.getBuildName());
			}

			// TODO pforster: proto start

			LOGGER.info(" ============= END OF BUILD IMPORT (success) ===========");
		} catch (Throwable e) {
			recordBuildImportFinished(summary, BuildImportStatus.FAILED, e.getMessage());
			LOGGER.error("  FAILURE on importing build " + summary.getIdentifier().getBranchName() + "/"
					+ summary.getBuildDescription().getName(), e);
			LOGGER.info(" ============= END OF BUILD IMPORT (failed) ===========");
		} finally {
			if (buildImportLog != null) {
				buildImportLog.unregisterAndFlush();
			}
		}
	}
	
	private synchronized void addSuccessfullyImportedBuild(final AvailableBuildsList availableBuilds,
			final BuildImportSummary summary) {
		recordBuildImportFinished(summary, BuildImportStatus.SUCCESS);
		availableBuilds.addImportedBuild(summary);
	}
	
	private synchronized void recordBuildImportFinished(final BuildImportSummary summary,
			final BuildImportStatus buildStatus) {
		recordBuildImportFinished(summary, buildStatus, null);
	}
	
	private synchronized void recordBuildImportFinished(BuildImportSummary summary,
			final BuildImportStatus buildStatus, final String statusMessage) {
		summary = buildImportSummaries.get(summary.getIdentifier());
		summary.setStatus(buildStatus);
		summary.setStatusMessage(statusMessage);
		summary.setImportDate(new Date());
		buildsBeeingImported.remove(summary.getIdentifier());
		buildsInProcessingQueue.remove(summary.getIdentifier());
		saveBuildImportSummaries(buildImportSummaries);
	}
	
	private static void saveBuildImportSummaries(final Map<BuildIdentifier, BuildImportSummary> buildImportSummaries) {
		List<BuildImportSummary> summariesToSave = new ArrayList<BuildImportSummary>(buildImportSummaries.values());
		ScenarioDocuAggregationDAO dao = new ScenarioDocuAggregationDAO(
				configurationRepository.getDocumentationDataDirectory());
		dao.saveBuildImportSummaries(summariesToSave);
	}
	
	/**
	 * Creates an executor that queues the passed tasks for execution by one single additional thread.
	 */
	private static ExecutorService newAsyncBuildImportExecutor() {
		return new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	}
	
}
