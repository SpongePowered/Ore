export class Category {
  static get values() {
    return [
      { id: 'admin_tools', name: 'Admin Tools', icon: 'server' },
      { id: 'chat', name: 'Chat', icon: 'comment' },
      { id: 'dev_tools', name: 'Developer Tools', icon: 'wrench' },
      { id: 'economy', name: 'Economy', icon: 'money-bill-alt' },
      { id: 'gameplay', name: 'Gameplay', icon: 'puzzle-piece' },
      { id: 'games', name: 'Games', icon: 'gamepad' },
      { id: 'protection', name: 'Protection', icon: 'lock' },
      { id: 'role_playing', name: 'Role Playing', icon: 'magic' },
      { id: 'world_management', name: 'World Management', icon: 'globe' },
      { id: 'misc', name: 'Miscellaneous', icon: 'asterisk' },
    ]
  }

  static fromId(id) {
    return this.values.filter((category) => category.id === id)[0]
  }
}

export class Platform {
  static get values() {
    return [
      {
        id: 'spongeapi',
        shortName: 'Sponge',
        name: 'Sponge Plugins',
        parent: true,
        color: { background: '#F7Cf0D', foreground: '#333333' },
        priority: 0,
        url: 'https://spongepowered.org/downloads',
        versions: {
          '8.x': ['8.0'],
          '7.x': ['7.0'],
          '6.x': ['6.0'],
          '5.x': ['5.0'],
          '4.x': ['4.0'],
        },
        minecraftVersions: {
          '1.12.x': ['7.0'],
          '1.11.x': ['6.0'],
          '1.10.x': ['5.0'],
          '1.8.9': ['4.0'],
        },
      },
      {
        id: 'spongeforge',
        shortName: 'SpongeForge',
        name: 'SpongeForge',
        color: { background: '#910020', foreground: '#FFFFFF' },
        priority: 2,
        url: 'https://www.spongepowered.org/downloads/spongeforge',
        versions: {
          '7.x': ['1.12.2-2838-7.1.10'],
          '6.x': ['1.11.2-2476-6.1.0-BETA-2792'],
          '5.x': ['1.10.2-2477-5.2.0-BETA-2793'],
        },
        minecraftVersions: {
          '1.12.x': ['1.12.2-2838-7.1.10'],
          '1.11.x': ['1.11.2-2476-6.1.0-BETA-2792'],
          '1.10.x': ['1.10.2-2477-5.2.0-BETA-2793'],
        },
      },
      {
        id: 'spongevanilla',
        shortName: 'SpongeVanilla',
        name: 'SpongeVanilla',
        color: { background: '#50C888', foreground: '#FFFFFF' },
        priority: 2,
        url: 'https://www.spongepowered.org/downloads/spongevanilla',
        versions: {
          '7.x': ['1.12.2-7.1.10'],
          '6.x': ['1.11.2-6.1.0-BETA-27'],
          '5.x': ['1.10.2-5.2.0-BETA-403'],
          '4.x': ['1.8.9-4.2.0-BETA-352'],
        },
        minecraftVersions: {
          '1.12.x': ['1.12.2-7.1.10'],
          '1.11.x': ['1.11.2-6.1.0-BETA-27'],
          '1.10.x': ['1.10.2-5.2.0-BETA-403'],
          '1.8.9': ['1.8.9-4.2.0-BETA-352'],
        },
      },
      {
        id: 'sponge',
        shortName: 'SpongeCommon',
        name: 'SpongeCommon',
        color: { background: '#5D5DFF', foreground: '#FFFFFF' },
        priority: 1,
        url: 'https://www.spongepowered.org/downloads',
        versions: {},
        minecraftVersions: {},
      },
      {
        id: 'lantern',
        shortName: 'Lantern',
        name: 'Lantern',
        color: { background: '#4EC1B4', foreground: '#FFFFFF' },
        priority: 2,
        url: 'https://www.lanternpowered.org/',
        versions: {},
        minecraftVersions: {},
      },
      {
        id: 'forge',
        shortName: 'Forge',
        name: 'Forge Mods',
        parent: true,
        color: { background: '#DFA86A', foreground: '#FFFFFF' },
        priority: 0,
        url: 'https://files.minecraftforge.net/',
        versions: {},
        minecraftVersions: {
          '1.15.2': ['31.1.0'],
          '1.15.1': ['30.0.51'],
          1.15: ['29.0.4'],
          '1.14.4': ['28.2.0'],
          '1.14.3': ['27.0.60'],
          '1.14.2': ['26.0.63'],
          '1.13.2': ['25.0.219'],
          '1.12.2': ['14.23.5.2768'],
          '1.12.1': ['14.22.1.2478'],
          1.12: ['14.21.1.2387'],
          '1.11.2': ['13.20.1.2386'],
          1.11: ['13.19.1.2189'],
          '1.10.2': ['12.18.3.2185'],
          '1.10': ['12.18.0.2000'],
          '1.9.4': ['12.17.0.2051'],
          1.9: ['12.16.1.1887'],
          '1.8.9': ['11.15.1.2318'],
        },
      },
    ]
  }

  static get keys() {
    return this.values.map((platform) => platform.id)
  }

  static isPlatformDependency(dependency) {
    return this.keys.includes(dependency.pluginId)
  }

  static fromId(id) {
    return this.values.filter((platform) => platform.id === id)[0]
  }

  static getPlatforms(dependencyIds) {
    /* TODO
        return this.values
            .filter(p => dependencyIds.includes(p.id))
            .groupBy(_.platformCategory)
            .flatMap(_._2.groupBy(_.priority).maxBy(_._1)._2)
         */

    return this.values.filter((p) => dependencyIds.includes(p.id))
  }
}

export const SortOptions = [
  { id: 'stars', name: 'Most Stars' },
  { id: 'downloads', name: 'Most Downloads' },
  { id: 'views', name: 'Most Views' },
  { id: 'newest', name: 'Newest' },
  { id: 'updated', name: 'Recently updated' },
  { id: 'only_relevance', name: 'Only relevance' },
  { id: 'recent_views', name: 'Recent Views' },
  { id: 'recent_downloads', name: 'Recent Downloads' },
]

export class Visibility {
  static get values() {
    return [
      { name: 'public', class: '', showModal: false },
      { name: 'new', class: 'project-new', showModal: false },
      { name: 'needsChanges', class: 'striped project-needsChanges', showModal: true },
      { name: 'needsApproval', class: 'striped project-needsChanges', showModal: false },
      { name: 'softDelete', class: 'striped project-hidden', showModal: true },
    ]
  }

  static fromName(name) {
    return this.values.filter((visibility) => visibility.name === name)[0]
  }
}

export class Permission {
  static get all() {
    return [
      Permission.ViewPublicInfo,
      Permission.EditOwnUserSettings,
      Permission.EditApiKeys,
      Permission.EditSubjectSettings,
      Permission.ManageSubjectMembers,
      Permission.IsSubjectOwner,
      Permission.IsSubjectMember,
      Permission.CreateProject,
      Permission.EditPage,
      Permission.DeleteProject,
      Permission.CreateVersion,
      Permission.EditVersion,
      Permission.DeleteVersion,
      Permission.EditChannel,
      Permission.CreateOrganization,
      Permission.PostAsOrganization,
      Permission.ModNotesAndFlags,
      Permission.SeeHidden,
      Permission.IsStaff,
      Permission.Reviewer,
      Permission.ViewHealth,
      Permission.ViewIp,
      Permission.ViewStats,
      Permission.ViewLogs,
      Permission.ManualValueChanges,
      Permission.HardDeleteProject,
      Permission.HardDeleteVersion,
      Permission.EditAllUserSettings,
    ]
  }
}

Permission.ViewPublicInfo = 'view_public_info'
Permission.EditOwnUserSettings = 'edit_own_user_settings'
Permission.EditApiKeys = 'edit_api_keys'
Permission.EditSubjectSettings = 'edit_subject_settings'
Permission.ManageSubjectMembers = 'manage_subject_members'
Permission.IsSubjectOwner = 'is_subject_owner'
Permission.IsSubjectMember = 'is_subject_member'
Permission.CreateProject = 'create_project'
Permission.EditPage = 'edit_page'
Permission.DeleteProject = 'delete_project'
Permission.CreateVersion = 'create_version'
Permission.EditVersion = 'edit_version'
Permission.DeleteVersion = 'delete_version'
Permission.EditChannel = 'edit_channel'
Permission.CreateOrganization = 'create_organization'
Permission.PostAsOrganization = 'post_as_organization'
Permission.ModNotesAndFlags = 'mod_notes_and_flags'
Permission.SeeHidden = 'see_hidden'
Permission.IsStaff = 'is_staff'
Permission.Reviewer = 'reviewer'
Permission.ViewHealth = 'view_health'
Permission.ViewIp = 'view_ip'
Permission.ViewStats = 'view_stats'
Permission.ViewLogs = 'view_logs'
Permission.ManualValueChanges = 'manual_value_changes'
Permission.HardDeleteProject = 'hard_delete_project'
Permission.HardDeleteVersion = 'hard_delete_version'
Permission.EditAllUserSettings = 'edit_all_user_settings'

export class FlagReason {
  static get values() {
    return [
      { value: 0, title: 'Inappropriate Content' },
      { value: 1, title: 'Impersonation or Deception' },
      { value: 2, title: 'Spam' },
      { value: 3, title: 'Malicious Intent' },
      { value: 4, title: 'Other' },
    ]
  }
}

export class Stability {
  static get values() {
    return [
      { id: 'recommended', title: 'Recommended', color: '#00C8FF' },
      { id: 'stable', title: 'Stable', color: '#00C800' },
      { id: 'beta', title: 'Beta', color: '#FFC800' },
      { id: 'alpha', title: 'Alpha', color: '#FF6000' },
      { id: 'bleeding', title: 'Bleeding', color: '#FF0000' },
      { id: 'unsupported', title: 'Unsupported', color: '#784646' },
      { id: 'broken', title: 'Broken', color: '#7F7F7F' },
    ]
  }

  static fromId(id) {
    return this.values.filter((stability) => stability.id === id)[0]
  }
}

export class ReleaseType {
  static get values() {
    return [
      { id: 'major_update', title: 'Major update', color: '#4080FF' },
      { id: 'minor_update', title: 'Minor update', color: '#009600' },
      { id: 'patches', title: 'Patches', color: '#7F7F7F' },
      { id: 'hotfix', title: 'Hotfix', color: '#C80000' },
    ]
  }

  static fromId(id) {
    return this.values.filter((releaseType) => releaseType.id === id)[0]
  }
}

export class Color {}

Color.Purple = '#B400FF'
Color.Violet = '#C87DFF'
Color.Magenta = '#E100E1'
Color.Blue = '#0000FF'
Color.LightBlue = '#B9F2FF'
Color.Quartz = '#E7FEFF'
Color.Aqua = '#0096FF'
Color.Cyan = '#00E1E1'
Color.Green = '#00DC00'
Color.DarkGreen = '#009600'
Color.Chartreuse = '#7FFF00'
Color.Amber = '#FFC800'
Color.Gold = '#CFB53B'
Color.Orange = '#FF8200'
Color.Red = '#DC0000'
Color.Silver = '#C0C0C0'
Color.Gray = '#A9A9A9'
Color.Transparent = 'Transparent'

export class Role {
  static get values() {
    return [
      this.OreAdmin,
      this.OreMod,
      this.SpongeLeader,
      this.TeamLeader,
      this.CommunityLeader,
      this.SpongeStaff,
      this.SpongeDev,
      this.OreDev,
      this.WebDev,
      this.Documenter,
      this.Support,
      this.Contributor,
      this.Advisor,
      this.StoneDonor,
      this.QuartzDonor,
      this.IronDonor,
      this.GoldDonor,
      this.DiamondDonor,
      this.ProjectOwner,
      this.ProjectAdmin,
      this.ProjectDeveloper,
      this.ProjectEditor,
      this.ProjectSupport,
      this.Organization,
      this.OrganizationOwner,
      this.OrganizationAdmin,
      this.OrganizationDev,
      this.OrganizationEditor,
      this.OrganizationSupport,
    ]
  }

  static categoryRoles(category) {
    return this.values.filter((role) => role.category === category)
  }

  static byId(id) {
    return this.values.filter((role) => role.name === id)[0]
  }

  static get projectRoles() {
    return this.categoryRoles('project')
  }

  static get orgRoles() {
    return this.categoryRoles('organization')
  }

  static get OreAdmin() {
    return {
      name: 'Ore_Admin',
      category: 'global',
      permissions: Permission.all,
      title: 'Ore Admin',
      color: Color.Red,
      isAssignable: true,
    }
  }

  static get OreMod() {
    return {
      name: 'Ore_Mod',
      category: 'global',
      permissions: [Permission.IsStaff, Permission.Reviewer, Permission.ModNotesAndFlags, Permission.SeeHidden],
      title: 'Ore Moderator',
      color: Color.Aqua,
      isAssignable: true,
    }
  }

  static get SpongeLeader() {
    return {
      name: 'Sponge_Leader',
      category: 'global',
      permissions: [],
      title: 'Sponge Leader',
      color: Color.Amber,
      isAssignable: true,
    }
  }

  static get TeamLeader() {
    return {
      name: 'Team_Leader',
      category: 'global',
      permissions: [],
      title: 'Team Leader',
      color: Color.Amber,
      isAssignable: true,
    }
  }

  static get CommunityLeader() {
    return {
      name: 'Community_Leader',
      category: 'global',
      permissions: [],
      title: 'Community Leader',
      color: Color.Amber,
      isAssignable: true,
    }
  }

  static get SpongeStaff() {
    return {
      name: 'Sponge_Staff',
      category: 'global',
      permissions: [],
      title: 'Sponge Staff',
      color: Color.Amber,
      isAssignable: true,
    }
  }

  static get SpongeDev() {
    return {
      name: 'Sponge_Developer',
      category: 'global',
      permissions: [],
      title: 'Sponge Developer',
      color: Color.Green,
      isAssignable: true,
    }
  }

  static get OreDev() {
    return {
      name: 'Ore_Dev',
      category: 'global',
      permissions: [Permission.ViewStats, Permission.ViewLogs, Permission.ViewHealth, Permission.ManualValueChanges],
      title: 'Ore Developer',
      color: Color.Orange,
      isAssignable: true,
    }
  }

  static get WebDev() {
    return {
      name: 'Web_Dev',
      category: 'global',
      permissions: [Permission.ViewLogs, Permission.ViewHealth],
      title: 'Web Developer',
      color: Color.Blue,
      isAssignable: true,
    }
  }

  static get Documenter() {
    return {
      name: 'Documenter',
      category: 'global',
      permissions: [],
      title: 'Documenter',
      color: Color.Aqua,
      isAssignable: true,
    }
  }

  static get Support() {
    return {
      name: 'Support',
      category: 'global',
      permissions: [],
      title: 'Support',
      color: Color.Aqua,
      isAssignable: true,
    }
  }

  static get Contributor() {
    return {
      name: 'Contributor',
      category: 'global',
      permissions: [],
      title: 'Contributor',
      color: Color.Green,
      isAssignable: true,
    }
  }

  static get Advisor() {
    return {
      name: 'Advisor',
      category: 'global',
      permissions: [],
      title: 'Advisor',
      color: Color.Aqua,
      isAssignable: true,
    }
  }

  static get StoneDonor() {
    return {
      name: 'Stone_Donor',
      category: 'global',
      permissions: [],
      title: 'Stone Donor',
      color: Color.Gray,
      isAssignable: true,
      priority: 5,
    }
  }

  static get QuartzDonor() {
    return {
      name: 'Quartz_Donor',
      category: 'global',
      permissions: [],
      title: 'Quartz Donor',
      color: Role.Quartz,
      isAssignable: true,
      priority: 4,
    }
  }

  static get IronDonor() {
    return {
      name: 'Iron_Donor',
      category: 'global',
      permissions: [],
      title: 'Iron Donor',
      color: Color.Silver,
      isAssignable: true,
      priority: 3,
    }
  }

  static get GoldDonor() {
    return {
      name: 'Gold_Donor',
      category: 'global',
      permissions: [],
      title: 'Gold Donor',
      color: Color.Gold,
      isAssignable: true,
      priority: 2,
    }
  }

  static get DiamondDonor() {
    return {
      name: 'Diamond_Donor',
      category: 'global',
      permissions: [],
      title: 'Diamond Donor',
      color: Color.LightBlue,
      isAssignable: true,
      priority: 1,
    }
  }

  static get ProjectOwner() {
    return {
      name: 'Project_Owner',
      category: 'project',
      permissions: [Permission.IsSubjectOwner, Permission.DeleteProject, ...Role.ProjectAdmin.permissions],
      title: 'Owner',
      color: Color.Transparent,
      isAssignable: false,
    }
  }

  static get ProjectAdmin() {
    return {
      name: 'Project_Admin',
      category: 'project',
      permissions: [
        Permission.EditSubjectSettings,
        Permission.ManageSubjectMembers,
        Permission.EditApiKeys,
        Permission.DeleteVersion,
        ...Role.ProjectDeveloper.permissions,
      ],
      title: 'Admin',
      color: Color.Transparent,
      isAssignable: true,
    }
  }

  static get ProjectDeveloper() {
    return {
      name: 'Project_Developer',
      category: 'project',
      permissions: [
        Permission.CreateVersion,
        Permission.EditVersion,
        Permission.EditChannel,
        ...Role.ProjectEditor.permissions,
      ],
      title: 'Developer',
      color: Color.Transparent,
      isAssignable: true,
    }
  }

  static get ProjectEditor() {
    return {
      name: 'Project_Editor',
      category: 'project',
      permissions: [Permission.EditPage, ...Role.ProjectSupport.permissions],
      title: 'Editor',
      color: Color.Transparent,
      isAssignable: true,
    }
  }

  static get ProjectSupport() {
    return {
      name: 'Project_Support',
      category: 'project',
      permissions: [Permission.IsSubjectMember],
      title: 'Support',
      color: Color.Transparent,
      isAssignable: true,
    }
  }

  static get Organization() {
    return {
      name: 'Organization',
      category: 'organization',
      permissions: Role.OrganizationOwner.permissions,
      title: 'Organization',
      color: Color.Purple,
      isAssignable: false,
    }
  }

  static get OrganizationOwner() {
    return {
      name: 'Organization_Owner',
      category: 'organization',
      permissions: [Permission.IsSubjectOwner, ...Role.ProjectOwner.permissions, ...Role.OrganizationAdmin.permissions],
      title: 'Owner',
      color: Color.Purple,
      isAssignable: false,
    }
  }

  static get OrganizationAdmin() {
    return {
      name: 'Organization_Admin',
      category: 'organization',
      permissions: [
        Permission.EditApiKeys,
        Permission.ManageSubjectMembers,
        Permission.EditOwnUserSettings,
        Permission.DeleteProject,
        Permission.DeleteVersion,
        ...Role.OrganizationDev.permissions,
      ],
      title: 'Admin',
      color: Color.Transparent,
      isAssignable: true,
    }
  }

  static get OrganizationDev() {
    return {
      name: 'Organization_Developer',
      category: 'organization',
      permissions: [
        Permission.CreateProject,
        Permission.EditSubjectSettings,
        ...Role.ProjectDeveloper.permissions,
        ...Role.OrganizationEditor.permissions,
      ],
      title: 'Developer',
      color: Color.Transparent,
      isAssignable: true,
    }
  }

  static get OrganizationEditor() {
    return {
      name: 'Organization_Editor',
      category: 'organization',
      permissions: [...Role.ProjectEditor.permissions, ...Role.OrganizationSupport.permissions],
      title: 'Editor',
      color: Color.Transparent,
      isAssignable: true,
    }
  }

  static get OrganizationSupport() {
    return {
      name: 'Organization_Support',
      category: 'organization',
      permissions: [Permission.PostAsOrganization, Permission.IsSubjectMember],
      title: 'Support',
      color: Color.Transparent,
      isAssignable: true,
    }
  }
}

export class Prompt {
  static get values() {
    return [this.ChangeAvatar]
  }

  static get ChangeAvatar() {
    return {
      id: 0,
      title: 'Change your avatar!',
      message: 'Welcome to your new organization! Start by changing your avatar by clicking on it.',
    }
  }

  static fromId(id) {
    return this.values.filter((prompt) => prompt.id === id)[0]
  }
}
