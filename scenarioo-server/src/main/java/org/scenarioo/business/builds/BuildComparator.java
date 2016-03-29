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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.scenarioo.api.ScenarioDocuReader;
import org.scenarioo.api.ScenarioDocuWriter;
import org.scenarioo.model.docu.entities.Build;
import org.scenarioo.model.docu.entities.DefaultDiffInfo;
import org.scenarioo.model.docu.entities.Scenario;
import org.scenarioo.model.docu.entities.UseCase;
import org.scenarioo.repository.ConfigurationRepository;
import org.scenarioo.repository.RepositoryLocator;
import org.scenarioo.rest.base.BuildIdentifier;

/**
 * TODO pforster: start proto
 */
public class BuildComparator {

	private final ConfigurationRepository configurationRepository = RepositoryLocator.INSTANCE
			.getConfigurationRepository();

	private final ScenarioDocuReader reader = new ScenarioDocuReader(
			configurationRepository.getDocumentationDataDirectory());


	public void compareBuilds(final BuildIdentifier buildIdentifier, final BuildIdentifier compareBuildIdentifier) {
		final ScenarioDocuWriter writer = new ScenarioDocuWriter(
				configurationRepository.getDocumentationDataDirectory(), buildIdentifier.getBranchName(),
				buildIdentifier.getBuildName());

		Build build = reader.loadBuild(buildIdentifier.getBranchName(), buildIdentifier.getBuildName());

		// TODO pforster: refactoring

		List<UseCase> useCases = reader.loadUsecases(buildIdentifier.getBranchName(), buildIdentifier.getBuildName());
		List<UseCase> compareUseCases = reader.loadUsecases(compareBuildIdentifier.getBranchName(),
				compareBuildIdentifier.getBuildName());

		DefaultDiffInfo buildDiffInfo = new DefaultDiffInfo();
		buildDiffInfo.setBranch(buildIdentifier.getBranchName());
		buildDiffInfo.setBuild(buildIdentifier.getBuildName());
		for (UseCase useCase : useCases) {
			if (StringUtils.isEmpty(useCase.getName())) {
				// TODO pforster: throw exception
			}

			UseCase compareUseCase = findUseCaseByName(compareUseCases, useCase.getName());
			if (compareUseCase == null) {
				buildDiffInfo.setAdded(buildDiffInfo.getAdded() + 1);
			} else {
				compareUseCases.remove(compareUseCase);

				List<Scenario> scenarios = reader.loadScenarios(buildIdentifier.getBranchName(),
						buildIdentifier.getBuildName(), useCase.getName());
				List<Scenario> compareScenarios = reader.loadScenarios(compareBuildIdentifier.getBranchName(),
						compareBuildIdentifier.getBuildName(), useCase.getName());

				DefaultDiffInfo useCaseDiffInfo = new DefaultDiffInfo();
				useCaseDiffInfo.setBranch(buildIdentifier.getBranchName());
				useCaseDiffInfo.setBuild(buildIdentifier.getBuildName());
				for (Scenario scenario : scenarios) {
					if (StringUtils.isEmpty(scenario.getName())) {
						// TODO pforster: throw exception
					}

					Scenario compareScenario = findScenarioByName(compareScenarios, scenario.getName());
					if (compareScenario == null) {
						useCaseDiffInfo.setAdded(useCaseDiffInfo.getAdded() + 1);
					} else {
						compareScenarios.remove(compareScenario);
					}
				}
				useCaseDiffInfo.setRemoved(compareScenarios.size());

				// TODO pforster: maybe a diff info already exists and needs to be updated.
				useCase.getDiffs().getDiffs().add(useCaseDiffInfo);

				writer.saveUseCase(useCase);
			}
		}
		buildDiffInfo.setRemoved(compareUseCases.size());

		// TODO pforster: maybe a diff info already exists and needs to be updated.
		build.getDiffs().getDiffs().add(buildDiffInfo);

		writer.saveBuildDescription(build);
	}

	private UseCase findUseCaseByName(final List<UseCase> compareUseCases, final String useCaseToFind) {
		for (UseCase compareUseCase : compareUseCases) {
			if (useCaseToFind.equals(compareUseCase.getName())) {
				return compareUseCase;
			}
		}
		return null;
	}

	private Scenario findScenarioByName(final List<Scenario> compareScenarios, final String scenarioToFind) {
		for (Scenario compareScenario : compareScenarios) {
			if (scenarioToFind.equals(compareScenario.getName())) {
				return compareScenario;
			}
		}
		return null;
	}

}
