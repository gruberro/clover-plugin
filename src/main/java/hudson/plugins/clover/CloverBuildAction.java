package hudson.plugins.clover;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import hudson.model.AbstractBuild;
import hudson.model.HealthReport;
import hudson.model.HealthReportingAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.plugins.clover.results.AbstractPackageAggregatedMetrics;
import hudson.plugins.clover.results.ClassCoverage;
import hudson.plugins.clover.results.FileCoverage;
import hudson.plugins.clover.results.PackageCoverage;
import hudson.plugins.clover.results.ProjectCoverage;
import hudson.plugins.clover.targets.CoverageMetric;
import hudson.plugins.clover.targets.CoverageTarget;
import org.kohsuke.stapler.StaplerProxy;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.RunAction2;
import org.jvnet.localizer.Localizable;


/**
 * A health reporter for the individual build page.
 *
 *
 * @author connollys
 * @since 03-Jul-2007 08:43:08
 */
public class CloverBuildAction extends AbstractPackageAggregatedMetrics implements HealthReportingAction, StaplerProxy, RunAction2 {
    public transient Run<?, ?> owner;
    private String buildBaseDir;
    private CoverageTarget healthyTarget;
    private CoverageTarget unhealthyTarget;

    private static final LoadingCache<CloverBuildAction,ProjectCoverage> reports = CacheBuilder.newBuilder().
        weakKeys().
        expireAfterAccess(60, TimeUnit.MINUTES).
        build(new CacheLoader<CloverBuildAction,ProjectCoverage>() {
            @Override public ProjectCoverage load(CloverBuildAction k) throws IOException {
                return k.computeResult();
            }
        });

    public HealthReport getBuildHealth() {
        if (healthyTarget == null || unhealthyTarget == null)
        {
            return null;
        }
        ProjectCoverage projectCoverage = getResult();
        Map<CoverageMetric, Integer> scores = healthyTarget.getRangeScores(unhealthyTarget, projectCoverage);
        int minValue = 100;
        CoverageMetric minKey = null;
        for (Map.Entry<CoverageMetric, Integer> e : scores.entrySet()) {
            if (e.getValue() < minValue) {
                minKey = e.getKey();
                minValue = e.getValue();
            }
        }
        if (minKey == null) return null;

        Localizable description = null;
        switch (minKey) {
            case METHOD:
                description = Messages._CloverBuildAction_MethodCoverage(
                        projectCoverage.getMethodCoverage().getPercentage(),
                        projectCoverage.getMethodCoverage().toString());
                break;
            case CONDITIONAL:
                description = Messages._CloverBuildAction_ConditionalCoverage(
                        projectCoverage.getConditionalCoverage().getPercentage(),
                        projectCoverage.getConditionalCoverage().toString());
                break;
            case STATEMENT:
                description = Messages._CloverBuildAction_StatementCoverage(
                        projectCoverage.getStatementCoverage().getPercentage(),
                        projectCoverage.getStatementCoverage().toString());
                break;
            case ELEMENT:
                description = Messages._CloverBuildAction_ElementCoverage(
                        projectCoverage.getElementCoverage().getPercentage(),
                        projectCoverage.getElementCoverage().toString());
                break;
            default:
                return null;
        }
        return new HealthReport(minValue, description);
    }

    public String getIconFileName() {
        return CloverProjectAction.ICON;
    }

    public String getDisplayName() {
        return Messages.CloverBuildAction_DisplayName();
    }

    public String getUrlName() {
        return "clover";
    }

    public Object getTarget() {
        return getResult();
    }

    public CloverBuildAction getPreviousResult() {
        return getPreviousResult(owner);
    }

    /** Gets the previous {@link CloverBuildAction} of the given build. */
    /*package*/
    static CloverBuildAction getPreviousResult(Run<?, ?> start) {
        Run<?, ?> b = start;
        while (true) {
            b = b.getPreviousBuild();
            if (b == null)
                return null;
            if (b.getResult() == Result.FAILURE)
                continue;
            CloverBuildAction r = b.getAction(CloverBuildAction.class);
            if (r != null)
                return r;
        }
    }

    CloverBuildAction(String workspacePath, ProjectCoverage r, CoverageTarget healthyTarget, CoverageTarget unhealthyTarget) {
        if (r != null) {
            reports.put(this, r);
        }
        this.buildBaseDir = workspacePath;
        if (this.buildBaseDir == null) {
            this.buildBaseDir = File.separator;
        } else if (!this.buildBaseDir.endsWith(File.separator)) {
            this.buildBaseDir += File.separator;
        }
        this.healthyTarget = healthyTarget;
        this.unhealthyTarget = unhealthyTarget;
    }

    @Override public void onAttached(Run<?,?> build) {
        ProjectCoverage c = reports.getIfPresent(this);
        if (c != null) {
            c.setOwner(build);
        }
    }

    @Override public void onLoad(Run<?,?> r) {
        owner = (AbstractBuild) r;
    }
    
    /** Obtains the detailed {@link CoverageReport} instance. */
    public synchronized ProjectCoverage getResult() {
        try {
            return reports.get(this);
        } catch (ExecutionException e) {
            logger.log(Level.WARNING, "Failed to load " + CloverPublisher.getCloverXmlReport(owner), e);
            return null;
        }
    }

    private ProjectCoverage computeResult() throws IOException {
        File reportFile = CloverPublisher.getCloverXmlReport(owner);
        ProjectCoverage r = CloverCoverageParser.parse(reportFile, buildBaseDir);
        r.setOwner(owner);
        return r;
    }

    // the following is ugly but I might need it

    /** {@inheritDoc} */
    public PackageCoverage findPackageCoverage(String name) {
        return getResult().findPackageCoverage(name);
    }

    /** {@inheritDoc} */
    public FileCoverage findFileCoverage(String name) {
        return getResult().findFileCoverage(name);
    }

    /** {@inheritDoc} */
    public ClassCoverage findClassCoverage(String name) {
        return getResult().findClassCoverage(name);
    }

    /** {@inheritDoc} */
    public int getPackages() {
        return getResult().getPackages();
    }

    /** {@inheritDoc} */
    @Override public int getFiles() {
        return getResult().getFiles();
    }

    /** {@inheritDoc} */
    @Override public int getClasses() {
        return getResult().getClasses();
    }

    /** {@inheritDoc} */
    @Override public int getLoc() {
        return getResult().getLoc();
    }

    /** {@inheritDoc} */
    @Override public int getNcloc() {
        return getResult().getNcloc();
    }

    /** {@inheritDoc} */
    @Override public Ratio getMethodCoverage() {
        return getResult().getMethodCoverage();
    }

    /** {@inheritDoc} */
    @Override public Ratio getStatementCoverage() {
        return getResult().getStatementCoverage();
    }

    /** {@inheritDoc} */
    @Override public Ratio getConditionalCoverage() {
        return getResult().getConditionalCoverage();
    }

    /** {@inheritDoc} */
    @Override public Ratio getElementCoverage() {
        return getResult().getElementCoverage();
    }

    /** {@inheritDoc} */
    @Override public int getConditionals() {
        return getResult().getConditionals();
    }

    /** {@inheritDoc} */
    @Override public int getMethods() {
        return getResult().getMethods();
    }

    /** {@inheritDoc} */
    @Override public int getCoveredstatements() {
        return getResult().getCoveredstatements();
    }

    /** {@inheritDoc} */
    @Override public int getCoveredmethods() {
        return getResult().getCoveredmethods();
    }

    /** {@inheritDoc} */
    @Override public int getCoveredconditionals() {
        return getResult().getCoveredconditionals();
    }

    /** {@inheritDoc} */
    @Override public int getStatements() {
        return getResult().getStatements();
    }

    /** {@inheritDoc} */
    @Override public int getCoveredelements() {
        return getResult().getCoveredelements();
    }

    /** {@inheritDoc} */
    @Override public int getElements() {
        return getResult().getElements();
    }

    private static final Logger logger = Logger.getLogger(CloverBuildAction.class.getName());

    public static CloverBuildAction load(String workspacePath, ProjectCoverage result, CoverageTarget healthyTarget, CoverageTarget unhealthyTarget) {
        return new CloverBuildAction(workspacePath, result, healthyTarget, unhealthyTarget);
    }
}
