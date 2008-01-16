package hudson.model;

import hudson.Util;
import hudson.security.Permission;
import hudson.security.ACL;
import hudson.security.PermissionGroup;
import hudson.scm.ChangeLogSet.Entry;
import hudson.search.CollectionSearchIndex;
import hudson.search.SearchIndexBuilder;
import hudson.util.RunList;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the rendering of the list of {@link TopLevelItem}s
 * that {@link Hudson} owns.
 *
 * @author Kohsuke Kawaguchi
 */
@ExportedBean
public abstract class View extends AbstractModelObject {

    /**
     * Gets all the items in this collection in a read-only view.
     */
    @Exported(name="jobs")
    public abstract Collection<TopLevelItem> getItems();

    /**
     * Gets the {@link TopLevelItem} of the given name.
     */
    public abstract TopLevelItem getItem(String name);

    /**
     * Checks if the job is in this collection.
     */
    public abstract boolean contains(TopLevelItem item);

    /**
     * Gets the name of all this collection.
     */
    @Exported(visibility=2,name="name")
    public abstract String getViewName();

    /**
     * Message displayed in the top page. Can be null. Includes HTML.
     */
    @Exported
    public abstract String getDescription();

    /**
     * Returns the path relative to the context root.
     *
     * Doesn't start with '/' but ends with '/'. (except when this is
     * Hudson, 
     */
    public abstract String getUrl();

    public String getSearchUrl() {
        return getUrl();
    }

    /**
     * Gets the absolute URL of this view.
     */
    @Exported(visibility=2,name="url")
    public String getAbsoluteUrl() {
        return Stapler.getCurrentRequest().getRootPath()+'/'+getUrl();
    }

    public Api getApi(final StaplerRequest req) {
        return new Api(this);
    }

    /**
     * Returns the {@link ACL} for this object.
     */
    public ACL getACL() {
        // TODO: this object should have its own ACL
        return Hudson.getInstance().getACL();
    }

    /**
     * Short for {@code getACL().checkPermission(p)}
     */
    public void checkPermission(Permission p) {
        getACL().checkPermission(p);
    }

    @ExportedBean(defaultVisibility=2)
    public static final class UserInfo implements Comparable<UserInfo> {
        private final User user;
        private Calendar lastChange;
        private AbstractProject project;

        UserInfo(User user, AbstractProject p, Calendar lastChange) {
            this.user = user;
            this.project = p;
            this.lastChange = lastChange;
        }

        @Exported
        public User getUser() {
            return user;
        }

        @Exported
        public Calendar getLastChange() {
            return lastChange;
        }

        @Exported
        public AbstractProject getProject() {
            return project;
        }

        /**
         * Returns a human-readable string representation of when this user was last active.
         */
        public String getLastChangeTimeString() {
            long duration = new GregorianCalendar().getTimeInMillis()-lastChange.getTimeInMillis();
            return Util.getTimeSpanString(duration);
        }

        public String getTimeSortKey() {
            return Util.XS_DATETIME_FORMATTER.format(lastChange.getTime());
        }

        public int compareTo(UserInfo that) {
            long rhs = that.lastChange.getTimeInMillis();
            long lhs = this.lastChange.getTimeInMillis();
            if(rhs>lhs) return 1;
            if(rhs<lhs) return -1;
            return 0;
        }
    }

    /**
     * Does this {@link View} has any associated user information recorded?
     */
    public final boolean hasPeople() {
        for (Item item : getItems()) {
            for (Job job : item.getAllJobs()) {
                if (job instanceof AbstractProject) {
                    AbstractProject<?,?> p = (AbstractProject) job;
                    for (AbstractBuild<?,?> build : p.getBuilds()) {
                        for (Entry entry : build.getChangeSet()) {
                            User user = entry.getAuthor();
                            if(user!=null)
                                return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Gets the users that show up in the changelog of this job collection.
     */
    public final People getPeople() {
        return new People();
    }

    @ExportedBean
    public final class People  {
        @Exported
        public final List<UserInfo> users;

        public People() {
            Map<User,UserInfo> users = new HashMap<User,UserInfo>();
            for (Item item : getItems()) {
                for (Job job : item.getAllJobs()) {
                    if (job instanceof AbstractProject) {
                        AbstractProject<?,?> p = (AbstractProject) job;
                        for (AbstractBuild<?,?> build : p.getBuilds()) {
                            for (Entry entry : build.getChangeSet()) {
                                User user = entry.getAuthor();

                                UserInfo info = users.get(user);
                                if(info==null)
                                    users.put(user,new UserInfo(user,p,build.getTimestamp()));
                                else
                                if(info.getLastChange().before(build.getTimestamp())) {
                                    info.project = p;
                                    info.lastChange = build.getTimestamp();
                                }
                            }
                        }
                    }
                }
            }

            ArrayList<UserInfo> list = new ArrayList<UserInfo>();
            list.addAll(users.values());
            Collections.sort(list);
            this.users = Collections.unmodifiableList(list);
        }

        public View getParent() {
            return View.this;
        }

        public Api getApi() {
            return new Api(this);
        }
    }


    @Override
    public SearchIndexBuilder makeSearchIndex() {
        return super.makeSearchIndex()
            .add(new CollectionSearchIndex() {// for jobs in the view
                protected TopLevelItem get(String key) { return getItem(key); }
                protected Collection<TopLevelItem> all() { return getItems(); }
            });
    }

    /**
     * Creates a new {@link Item} in this collection.
     *
     * @return
     *      null if fails.
     */
    public abstract Item doCreateItem( StaplerRequest req, StaplerResponse rsp ) throws IOException, ServletException;

    public void doRssAll( StaplerRequest req, StaplerResponse rsp ) throws IOException, ServletException {
        rss(req, rsp, " all builds", new RunList(this));
    }

    public void doRssFailed( StaplerRequest req, StaplerResponse rsp ) throws IOException, ServletException {
        rss(req, rsp, " failed builds", new RunList(this).failureOnly());
    }

    private void rss(StaplerRequest req, StaplerResponse rsp, String suffix, RunList runs) throws IOException, ServletException {
        RSS.forwardToRss(getDisplayName()+ suffix, getUrl(),
            runs.newBuilds(), Run.FEED_ADAPTER, req, rsp );
    }

    public static final Comparator<View> SORTER = new Comparator<View>() {
        public int compare(View lhs, View rhs) {
            return lhs.getViewName().compareTo(rhs.getViewName());
        }
    };

    public static final PermissionGroup PERMISSIONS = new PermissionGroup(View.class,Messages._View_Permissions_Title());
    /**
     * Permission to create new jobs.
     */
    public static final Permission CREATE = new Permission(PERMISSIONS,"Create", Permission.CREATE);
    public static final Permission DELETE = new Permission(PERMISSIONS,"Delete", Permission.DELETE);
    public static final Permission CONFIGURE = new Permission(PERMISSIONS,"Configure", Permission.CONFIGURE);

    // to simplify access from Jelly
    public static Permission getItemCreatePermission() {
        return Item.CREATE;
    }
}
